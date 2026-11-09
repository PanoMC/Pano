package com.panomc.platform.route.api.panel.settings

import org.h2.tools.Recover
import java.io.File
import java.nio.file.Files

/**
 * Checks an uploaded LuckPerms H2 file before it is opened.
 *
 * Opening an H2 database replays the SQL stored in it, and some of that SQL runs code: a trigger
 * or an alias with source is compiled and loaded, a linked table connects somewhere else, a table
 * engine instantiates a class. A LuckPerms file only ever holds tables, indexes and constraints,
 * so anything else means the file was crafted and it is refused.
 *
 * The stored SQL is read with H2's recovery tool, which reads the file without opening the
 * database and so without executing any of it.
 */
object LuckPermsH2Guard {
    private const val SCHEMA_MARKER = "---- Schema ----"

    /** Statement starts a LuckPerms schema (and the recovery tool's own glue) can have. */
    private val ALLOWED_STARTS = listOf(
        "CREATE USER ",
        "CREATE CACHED TABLE ",
        "CREATE MEMORY TABLE ",
        "CREATE TABLE ",
        "CREATE INDEX ",
        "CREATE UNIQUE INDEX ",
        "CREATE PRIMARY KEY ",
        "CREATE SCHEMA ",
        "CREATE SEQUENCE ",
        "CREATE ROLE ",
        "ALTER TABLE ",
        "GRANT ",
        "COMMENT ON ",
        "SET ",
        "INSERT INTO ",
        "DROP TABLE "
    )

    /** Lines the recovery tool adds itself; they are not part of the file. */
    private val RECOVER_GLUE = setOf(
        "DROP ALIAS READ_BLOB_MAP;",
        "DROP ALIAS READ_CLOB_MAP;"
    )

    /** Keywords that, anywhere in the stored schema, mean code would run or another database would be reached. */
    private val FORBIDDEN = Regex("""\b(TRIGGER|ALIAS|AGGREGATE|ENGINE|LINKED|RUNSCRIPT|JAVA_OBJECT|LINK_SCHEMA|INIT|SERIALIZER|LISTENER)\b""", RegexOption.IGNORE_CASE)

    /** Throws [IllegalArgumentException] when [dbFile] holds anything a LuckPerms database never does. */
    fun requireSafe(dbFile: File) {
        val sql = recoverScript(dbFile) ?: throw IllegalArgumentException("The database file could not be read.")
        val unsafe = unsafeStatements(sql)
        require(unsafe.isEmpty()) { "The database file contains statements a LuckPerms database never has." }
    }

    /** The statements of the stored schema in [recoverScript] output that are not allowed. */
    fun unsafeStatements(script: String): List<String> {
        val lines = script.lines()
        val start = lines.indexOfLast { it == SCHEMA_MARKER }
        if (start < 0) return listOf("<no schema section>")

        val schema = lines.drop(start + 1).filter { it !in RECOVER_GLUE }

        val unsafe = mutableListOf<String>()

        schema.filter { FORBIDDEN.containsMatchIn(it) }.forEach { unsafe += it }

        // Statement starts are lines that do not continue the previous statement (column lists
        // and closing parentheses of a CREATE TABLE are indented or start with ')').
        schema
            .filter { it.isNotBlank() && !it.first().isWhitespace() && !it.startsWith(")") }
            .filter { line -> ALLOWED_STARTS.none { line.startsWith(it) } }
            .forEach { unsafe += it }

        return unsafe.distinct()
    }

    private fun recoverScript(dbFile: File): String? {
        val dir = Files.createTempDirectory("pano-lp-h2-check").toFile()

        return try {
            dbFile.copyTo(File(dir, "check.mv.db"))
            Recover().runTool("-dir", dir.absolutePath, "-db", "check")
            File(dir, "check.h2.sql").takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        } finally {
            dir.deleteRecursively()
        }
    }
}
