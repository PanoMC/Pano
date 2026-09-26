package com.panomc.platform.archive.db

import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcException.Code
import com.panomc.platform.archive.db.SqlToken.Type
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Imports a logical dump (Pano-native or `mariadb-dump --hex-blob --skip-routines --skip-triggers
 * --skip-events`) statement by statement, allowing only:
 *
 * - `CREATE TABLE [IF NOT EXISTS] <prefix table> (...)` without subqueries, LIKE, qualified names,
 *   DATA/INDEX DIRECTORY, partitions, variables, conditional comments, foreign keys to other tables,
 *   or an engine outside [ALLOWED_ENGINES];
 * - `INSERT [IGNORE] INTO <prefix table> [(cols)] VALUES (literal, ...), ...` with literals only;
 * - `DROP TABLE IF EXISTS <prefix table>, ...`;
 * - benign `SET` (time_zone, foreign_key_checks, unique_checks executed; names, character sets,
 *   sql_mode, sql_notes accepted and ignored) and `LOCK TABLES` / `UNLOCK TABLES` on prefix tables
 *   (ignored), plus statements that are nothing but executable comments (ignored).
 *
 * Everything else (routines, triggers, views, DEFINER, GRANT, other prefixes or databases, `;`
 * smuggled in any form) fails with [Code.UNSAFE_SQL]. Statements are re-rendered from their tokens,
 * so comments never reach the server; the session sql_mode is pinned so the server lexes strings the
 * same way [SqlTokenizer] did.
 */
class SanitisedSqlImporter(private val prefix: String, private val maxStatementChars: Long = SqlTokenizer.DEFAULT_MAX_STATEMENT_CHARS) {
    init {
        require(PREFIX.matches(prefix)) { "Invalid table prefix." }
    }

    sealed class Action {
        object Skip : Action()
        data class Execute(val sql: String, val kind: Kind, val table: String? = null, val rows: Int = 0) : Action()
    }

    enum class Kind { CREATE, INSERT, DROP, SET }

    data class Summary(val statements: Int, val tables: Set<String>, val rows: Long)

    /** Parses and checks the whole dump without touching a database. */
    fun validate(reader: Reader): Summary {
        val summary = SummaryBuilder()
        val tokenizer = SqlTokenizer(reader, maxStatementChars)

        while (true) {
            val statement = tokenizer.nextStatement() ?: break

            summary.add(classify(statement))
        }

        return summary.build()
    }

    fun validateGzip(input: InputStream): Summary = validate(gzipReader(input))

    /**
     * Runs the dump on [connection] (one session: the session settings below must hold for every
     * statement). Reading happens on the IO dispatcher; each allowed statement is awaited in order.
     */
    suspend fun import(reader: Reader, connection: SqlConnection): Summary {
        SESSION_SETUP.forEach { connection.query(it).execute().coAwait() }

        val summary = SummaryBuilder()
        val tokenizer = SqlTokenizer(reader, maxStatementChars)

        while (true) {
            val statement = withContext(Dispatchers.IO) { tokenizer.nextStatement() } ?: break
            val action = classify(statement)

            summary.add(action)

            if (action is Action.Execute) {
                connection.query(action.sql).execute().coAwait()
            }
        }

        connection.query("SET SESSION foreign_key_checks = 1").execute().coAwait()
        connection.query("SET SESSION unique_checks = 1").execute().coAwait()

        return summary.build()
    }

    suspend fun importGzip(input: InputStream, connection: SqlConnection): Summary = import(gzipReader(input), connection)

    /** Decides what to do with one statement; throws [Code.UNSAFE_SQL] for anything not allowed. */
    fun classify(tokens: List<SqlToken>): Action {
        if (tokens.isEmpty() || tokens.all { it.type == Type.CONDITIONAL }) {
            return Action.Skip
        }

        if (tokens.any { it.type == Type.CONDITIONAL }) {
            reject("an executable comment inside a statement", tokens)
        }

        if (tokens.any { it.type == Type.VARIABLE }) {
            reject("a variable", tokens)
        }

        val first = tokens[0]

        return when {
            first.isWord("CREATE") -> createTable(tokens)
            first.isWord("INSERT") -> insert(tokens)
            first.isWord("DROP") -> dropTable(tokens)
            first.isWord("SET") -> set(tokens)
            first.isWord("LOCK") -> lockTables(tokens)
            first.isWord("UNLOCK") && tokens.size == 2 && tokens[1].isWord("TABLES") -> Action.Skip
            else -> reject("a statement that is not CREATE TABLE, INSERT, DROP TABLE or SET", tokens)
        }
    }

    private fun createTable(tokens: List<SqlToken>): Action {
        val cursor = Cursor(tokens)

        cursor.expectWord("CREATE")
        cursor.expectWord("TABLE")

        if (cursor.peekWord("IF")) {
            cursor.expectWord("IF")
            cursor.expectWord("NOT")
            cursor.expectWord("EXISTS")
        }

        val table = cursor.table()

        if (!cursor.peekPunct('(')) {
            reject("CREATE TABLE without a column list", tokens)
        }

        var depth = 0
        var closedAt = -1

        tokens.forEachIndexed { index, token ->
            when {
                token.type == Type.WORD && token.raw.uppercase() in DENIED_CREATE_WORDS ->
                    reject("${token.raw.uppercase()} in CREATE TABLE", tokens)

                token.isPunct('.') -> reject("a qualified name in CREATE TABLE", tokens)
                token.isPunct('(') -> depth++
                token.isPunct(')') -> {
                    depth--

                    if (depth < 0) {
                        reject("unbalanced parentheses", tokens)
                    }

                    if (depth == 0 && closedAt < 0) {
                        closedAt = index
                    }
                }

                token.isWord("REFERENCES") -> Cursor(tokens, index + 1).table()
                token.isWord("ENGINE") -> {
                    var next = index + 1

                    if (tokens.getOrNull(next)?.isPunct('=') == true) {
                        next++
                    }

                    val engine = tokens.getOrNull(next)?.value?.lowercase()

                    if (engine !in ALLOWED_ENGINES) {
                        reject("ENGINE $engine", tokens)
                    }
                }
            }
        }

        if (depth != 0) {
            reject("unbalanced parentheses", tokens)
        }

        // Table options after the column list are key [=] value pairs, never another parenthesis.
        if (tokens.drop(closedAt + 1).any { it.isPunct('(') || it.isPunct(')') }) {
            reject("parenthesised table options", tokens)
        }

        return Action.Execute(render(tokens), Kind.CREATE, table)
    }

    private fun insert(tokens: List<SqlToken>): Action {
        val cursor = Cursor(tokens)

        cursor.expectWord("INSERT")

        if (cursor.peekWord("IGNORE")) {
            cursor.next()
        }

        cursor.expectWord("INTO")

        val table = cursor.table()

        if (cursor.peekPunct('(')) {
            cursor.next()

            while (true) {
                cursor.identifier()

                if (cursor.peekPunct(',')) {
                    cursor.next()
                    continue
                }

                cursor.expectPunct(')')
                break
            }
        }

        if (!cursor.peekWord("VALUES") && !cursor.peekWord("VALUE")) {
            reject("INSERT without VALUES", tokens)
        }

        cursor.next()

        var rows = 0

        while (true) {
            cursor.expectPunct('(')

            while (true) {
                cursor.literal()

                if (cursor.peekPunct(',')) {
                    cursor.next()
                    continue
                }

                cursor.expectPunct(')')
                break
            }

            rows++

            if (cursor.peekPunct(',')) {
                cursor.next()
                continue
            }

            break
        }

        cursor.expectEnd()

        return Action.Execute(render(tokens), Kind.INSERT, table, rows)
    }

    private fun dropTable(tokens: List<SqlToken>): Action {
        val cursor = Cursor(tokens)

        cursor.expectWord("DROP")
        cursor.expectWord("TABLE")
        cursor.expectWord("IF")
        cursor.expectWord("EXISTS")

        while (true) {
            cursor.table()

            if (cursor.peekPunct(',')) {
                cursor.next()
                continue
            }

            break
        }

        cursor.expectEnd()

        return Action.Execute(render(tokens), Kind.DROP)
    }

    private fun lockTables(tokens: List<SqlToken>): Action {
        val cursor = Cursor(tokens)

        cursor.expectWord("LOCK")
        cursor.expectWord("TABLES")

        while (true) {
            cursor.table()

            if (!cursor.peekWord("WRITE") && !cursor.peekWord("READ")) {
                reject("LOCK TABLES without READ/WRITE", tokens)
            }

            cursor.next()

            if (cursor.peekPunct(',')) {
                cursor.next()
                continue
            }

            break
        }

        cursor.expectEnd()

        return Action.Skip
    }

    private fun set(tokens: List<SqlToken>): Action {
        val cursor = Cursor(tokens)

        cursor.expectWord("SET")

        if (cursor.peekWord("NAMES")) {
            cursor.next()

            if (cursor.next().type.let { it != Type.WORD && it != Type.STRING }) {
                reject("SET NAMES to a non-literal", tokens)
            }

            if (cursor.peekWord("COLLATE")) {
                cursor.next()
                cursor.word()
            }

            cursor.expectEnd()

            return Action.Skip
        }

        if (cursor.peekWord("SESSION") || cursor.peekWord("LOCAL")) {
            cursor.next()
        }

        val name = cursor.word().lowercase()

        cursor.expectPunct('=')

        val value = cursor.next()

        cursor.expectEnd()

        return when (name) {
            "foreign_key_checks", "unique_checks" -> {
                if (value.type != Type.NUMBER || (value.raw != "0" && value.raw != "1")) {
                    reject("SET $name to something other than 0 or 1", tokens)
                }

                Action.Execute("SET SESSION $name = ${value.raw}", Kind.SET)
            }

            "time_zone" -> {
                val zone = if (value.type == Type.STRING) value.raw.substring(1, value.raw.length - 1) else ""

                if (!TIME_ZONE.matches(zone)) {
                    reject("SET time_zone to $zone", tokens)
                }

                Action.Execute("SET SESSION time_zone = '$zone'", Kind.SET)
            }

            in IGNORED_SETTINGS -> {
                if (value.type != Type.STRING && value.type != Type.WORD && value.type != Type.NUMBER) {
                    reject("SET $name to a non-literal", tokens)
                }

                Action.Skip
            }

            else -> reject("SET $name", tokens)
        }
    }

    private fun render(tokens: List<SqlToken>) = tokens.joinToString(" ") { it.raw }

    private fun isPrefixTable(name: String) = TABLE_NAME.matches(name) && name.startsWith(prefix)

    private inner class Cursor(val tokens: List<SqlToken>, var index: Int = 0) {
        fun peek(): SqlToken? = tokens.getOrNull(index)

        fun peekWord(word: String) = peek()?.isWord(word) == true

        fun peekPunct(char: Char) = peek()?.isPunct(char) == true

        fun next(): SqlToken = tokens.getOrNull(index++) ?: reject("an incomplete statement", tokens)

        fun expectWord(word: String) {
            if (!next().isWord(word)) {
                reject("an unexpected token where $word was expected", tokens)
            }
        }

        fun expectPunct(char: Char) {
            if (!next().isPunct(char)) {
                reject("an unexpected token where '$char' was expected", tokens)
            }
        }

        fun expectEnd() {
            if (index != tokens.size) {
                reject("trailing tokens", tokens)
            }
        }

        fun word(): String {
            val token = next()

            if (token.type != Type.WORD) {
                reject("an unexpected token", tokens)
            }

            return token.raw
        }

        fun identifier(): String {
            val token = next()

            if (token.type != Type.IDENT && token.type != Type.WORD) {
                reject("an unexpected token where a name was expected", tokens)
            }

            if (peekPunct('.')) {
                reject("a qualified name", tokens)
            }

            return token.value
        }

        fun table(): String {
            val name = identifier()

            if (!isPrefixTable(name)) {
                reject("table $name outside the prefix '$prefix'", tokens)
            }

            return name
        }

        fun literal() {
            val token = next()

            when {
                token.type == Type.STRING || token.type == Type.HEX || token.type == Type.BIT || token.type == Type.NUMBER -> {}
                token.isWord("NULL") || token.isWord("TRUE") || token.isWord("FALSE") -> {}
                token.isPunct('-') || token.isPunct('+') -> {
                    if (next().type != Type.NUMBER) {
                        reject("a sign without a number", tokens)
                    }
                }

                token.type == Type.WORD && CHARSET_INTRODUCER.matches(token.raw) -> {
                    val literal = next()

                    if (literal.type != Type.STRING && literal.type != Type.HEX) {
                        reject("an introducer without a string", tokens)
                    }
                }

                else -> reject("a non-literal value (${token.raw})", tokens)
            }
        }
    }

    private class SummaryBuilder {
        var statements = 0
        val tables = LinkedHashSet<String>()
        var rows = 0L

        fun add(action: Action) {
            if (action is Action.Execute) {
                statements++
                rows += action.rows

                if (action.kind == Kind.CREATE) {
                    action.table?.let { tables.add(it) }
                }
            }
        }

        fun build() = Summary(statements, tables, rows)
    }

    private fun reject(reason: String, tokens: List<SqlToken>): Nothing {
        val preview = tokens.take(6).joinToString(" ") { it.raw.take(40) }

        throw PanoArcException(Code.UNSAFE_SQL, "The database dump contains $reason: $preview")
    }

    companion object {
        val PREFIX = Regex("^[A-Za-z0-9_]{0,32}$")
        private val TABLE_NAME = Regex("^[A-Za-z0-9_]{1,64}$")
        private val TIME_ZONE = Regex("^([+-]\\d{2}:\\d{2}|UTC|SYSTEM)$")
        private val CHARSET_INTRODUCER = Regex("^(_[A-Za-z0-9]+|[Nn])$")

        val ALLOWED_ENGINES = setOf("innodb", "aria", "myisam", "memory")

        private val IGNORED_SETTINGS = setOf(
            "character_set_client", "character_set_results", "collation_connection", "sql_mode", "sql_notes",
            "names"
        )

        private val DENIED_CREATE_WORDS = setOf(
            "SELECT", "LIKE", "UNION", "INTO", "DEFINER", "DIRECTORY", "CONNECTION", "PROCEDURE", "FUNCTION",
            "TRIGGER", "EVENT", "VIEW", "GRANT", "REVOKE", "LOAD_FILE", "OUTFILE", "DUMPFILE", "PARTITION",
            "PARTITIONS", "SERVER", "SOCKET", "PASSWORD", "TEMPORARY", "SEQUENCE", "EXECUTE", "PREPARE",
            "HANDLER", "SLEEP", "BENCHMARK", "TABLESPACE"
        )

        /** Pinned before the first statement: default lexing (no NO_BACKSLASH_ESCAPES / ANSI_QUOTES). */
        private val SESSION_SETUP = listOf(
            "SET SESSION sql_mode = 'NO_AUTO_VALUE_ON_ZERO'",
            "SET SESSION time_zone = '+00:00'",
            "SET SESSION foreign_key_checks = 0",
            "SET SESSION unique_checks = 0"
        )

        fun gzipReader(input: InputStream): Reader =
            InputStreamReader(GZIPInputStream(input, 64 * 1024), StandardCharsets.UTF_8).buffered(64 * 1024)
    }
}
