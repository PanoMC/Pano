package com.panomc.platform.archive.db

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/**
 * Pano's own logical MariaDB dump (archive-format.md section 2, `dumpTool: pano-native`): self-hosted
 * Pano has no dump tool, so this writes what `mariadb-dump --single-transaction --skip-routines
 * --skip-triggers --skip-events --hex-blob` would for the instance's tables, and nothing else.
 *
 * - One connection, one `REPEATABLE READ` transaction `WITH CONSISTENT SNAPSHOT`: every table (and
 *   the scheme versions) come from the same point in time without locking writers.
 * - Only base tables whose name starts with [prefix]; `SHOW CREATE TABLE` minus `AUTO_INCREMENT=`
 *   and any `DEFINER=`, preceded by `DROP TABLE IF EXISTS`.
 * - Rows through a server-side cursor, as extended INSERTs of at most [batchRows] rows / about
 *   [batchBytes] characters. The server renders every value as text (`CAST(... AS CHAR)`, binary
 *   columns as `HEX()`), so no type is lost in a client-side conversion; `TIMESTAMP` values are read
 *   and written in `+00:00`. Generated columns are left out (the server recomputes them).
 *
 * The output is exactly what [SanitisedSqlImporter] accepts.
 */
class PanoNativeDumper(
    private val prefix: String,
    private val batchRows: Int = 500,
    private val batchBytes: Int = 1024 * 1024
) {
    init {
        require(SanitisedSqlImporter.PREFIX.matches(prefix)) { "Invalid table prefix." }
    }

    data class Result(val tables: List<String>, val rows: Long, val schemeVersions: Map<String, Int>)

    private data class Column(val name: String, val kind: ColumnKind)

    private enum class ColumnKind { NUMBER, BINARY, BIT, TEXT }

    /** Dumps into [output] as gzip (the `db/dump.sql.gz` entry); [output] is finished, not closed. */
    suspend fun dumpGzip(connection: SqlConnection, output: OutputStream): Result {
        val gzip = GZIPOutputStream(NonClosingStream(output), 64 * 1024)
        val result = dump(connection, gzip)

        withContext(Dispatchers.IO) { gzip.finish(); gzip.flush() }

        return result
    }

    /** Dumps plain SQL into [output] (flushed, not closed). */
    suspend fun dump(connection: SqlConnection, output: OutputStream): Result {
        val writer = OutputStreamWriter(NonClosingStream(output), StandardCharsets.UTF_8).buffered(64 * 1024)

        listOf(
            "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ",
            "SET SESSION time_zone = '+00:00'",
            "SET SESSION sql_mode = ''",
            "SET SESSION sql_quote_show_create = 1",
            "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY"
        ).forEach { connection.query(it).execute().coAwait() }

        try {
            val tables = connection
                .query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")
                .execute().coAwait()
                .map { it.getString(0) }
                .filter { it.startsWith(prefix) && TABLE_NAME.matches(it) }

            write(writer, "-- Pano native dump v1 (prefix '$prefix')\n")
            write(writer, "SET time_zone = '+00:00';\nSET foreign_key_checks = 0;\nSET unique_checks = 0;\n\n")

            var rows = 0L

            tables.forEach { table -> rows += dumpTable(connection, writer, table) }

            write(writer, "SET foreign_key_checks = 1;\nSET unique_checks = 1;\n")
            withContext(Dispatchers.IO) { writer.flush() }

            val schemeVersions = readSchemeVersions(connection, tables)

            return Result(tables, rows, schemeVersions)
        } finally {
            connection.query("ROLLBACK").execute().coAwait()
        }
    }

    private suspend fun dumpTable(connection: SqlConnection, writer: Writer, table: String): Long {
        val quoted = SqlLiterals.identifier(table)
        val create = connection.query("SHOW CREATE TABLE $quoted").execute().coAwait().first().getString(1)

        write(writer, "DROP TABLE IF EXISTS $quoted;\n${cleanCreateTable(create)};\n\n")

        val columns = connection
            .preparedQuery(
                "SELECT COLUMN_NAME, DATA_TYPE, EXTRA FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION"
            )
            .execute(Tuple.of(table)).coAwait()
            .filter { row -> GENERATED.none { row.getString(2)?.uppercase()?.contains(it) == true } }
            .map { Column(it.getString(0), kind(it.getString(1))) }

        if (columns.isEmpty()) {
            return 0
        }

        val select = columns.joinToString(", ") {
            val column = SqlLiterals.identifier(it.name)

            when (it.kind) {
                ColumnKind.BINARY -> "HEX($column)"
                ColumnKind.BIT -> "CAST($column AS UNSIGNED)"
                else -> "CAST($column AS CHAR)"
            }
        }

        val insertPrefix = "INSERT INTO $quoted (${columns.joinToString(",") { SqlLiterals.identifier(it.name) }}) VALUES\n"
        val statement = connection.prepare("SELECT $select FROM $quoted").coAwait()
        val cursor = statement.cursor()
        val batch = StringBuilder()
        var batchCount = 0
        var total = 0L

        suspend fun flush() {
            if (batchCount > 0) {
                write(writer, insertPrefix)
                write(writer, batch.append(";\n").toString())
                batch.setLength(0)
                batchCount = 0
            }
        }

        try {
            do {
                val rowSet = cursor.read(batchRows).coAwait()

                rowSet.forEach { row ->
                    if (batchCount > 0) {
                        batch.append(",\n")
                    }

                    batch.append('(')

                    columns.forEachIndexed { index, column ->
                        if (index > 0) {
                            batch.append(',')
                        }

                        val value = row.getValue(index)?.toString()

                        batch.append(
                            when (column.kind) {
                                ColumnKind.BINARY -> SqlLiterals.hex(value)
                                ColumnKind.NUMBER, ColumnKind.BIT -> SqlLiterals.number(value)
                                ColumnKind.TEXT -> SqlLiterals.string(value)
                            }
                        )
                    }

                    batch.append(')')
                    batchCount++
                    total++

                    if (batchCount >= batchRows || batch.length >= batchBytes) {
                        flush()
                    }
                }
            } while (cursor.hasMore())

            flush()
        } finally {
            cursor.close().coAwait()
            statement.close().coAwait()
        }

        write(writer, "\n")

        return total
    }

    /** core = the highest numeric key without a pluginId; each plugin = its highest key. */
    private suspend fun readSchemeVersions(connection: SqlConnection, tables: List<String>): Map<String, Int> {
        val table = prefix + SCHEME_VERSION_TABLE

        if (table !in tables) {
            return emptyMap()
        }

        val rows = connection.query("SELECT * FROM ${SqlLiterals.identifier(table)}").execute().coAwait()
        val hasPluginId = rows.columnsNames().contains("pluginId")
        val versions = LinkedHashMap<String, Int>()

        rows.forEach { row ->
            val key = row.getValue("key")?.toString()?.toIntOrNull() ?: return@forEach
            val owner = if (hasPluginId) row.getString("pluginId") ?: CORE else CORE

            versions[owner] = maxOf(versions[owner] ?: 0, key)
        }

        return versions
    }

    private suspend fun write(writer: Writer, text: String) = withContext(Dispatchers.IO) { writer.write(text) }

    private class NonClosingStream(output: OutputStream) : java.io.FilterOutputStream(output) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    companion object {
        const val DUMP_TOOL = "pano-native"
        const val CORE = "core"
        const val SCHEME_VERSION_TABLE = "scheme_version"

        private val TABLE_NAME = Regex("^[A-Za-z0-9_]{1,64}$")
        private val GENERATED = listOf("VIRTUAL", "STORED", "PERSISTENT", "GENERATED")
        private val AUTO_INCREMENT = Regex("\\s+AUTO_INCREMENT=\\d+")
        private val DEFINER = Regex("\\s*DEFINER\\s*=\\s*(`[^`]*`|'[^']*'|\\S+)@(`[^`]*`|'[^']*'|\\S+)", RegexOption.IGNORE_CASE)

        private val NUMBER_TYPES = setOf("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real")
        private val BINARY_TYPES = setOf(
            "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob",
            "geometry", "point", "linestring", "polygon", "multipoint", "multilinestring", "multipolygon", "geometrycollection"
        )

        private fun kind(dataType: String?): ColumnKind = when (dataType?.lowercase()) {
            in NUMBER_TYPES -> ColumnKind.NUMBER
            in BINARY_TYPES -> ColumnKind.BINARY
            "bit" -> ColumnKind.BIT
            else -> ColumnKind.TEXT
        }

        /** `SHOW CREATE TABLE` output without the `AUTO_INCREMENT=` counter and any `DEFINER=`. */
        fun cleanCreateTable(create: String): String = create.replace(AUTO_INCREMENT, "").replace(DEFINER, "")
    }
}
