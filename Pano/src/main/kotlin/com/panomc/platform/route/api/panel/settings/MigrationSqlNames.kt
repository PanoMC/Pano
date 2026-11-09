package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.error.InvalidData
import java.io.File

/**
 * Checks for the table names and database files the migration uploads read from. Table names end
 * up inside SQL (`SELECT * FROM `<name>``), so only plain identifier characters are accepted.
 */
object MigrationSqlNames {
    private val TABLE_PREFIX = Regex("^[A-Za-z0-9_]{0,32}$")
    private val TABLE_NAME = Regex("^[A-Za-z0-9_]{1,64}$")

    fun tablePrefix(value: String): String =
        value.takeIf { TABLE_PREFIX.matches(it) }
            ?: throw InvalidData(extras = mapOf("message" to "The table prefix may only contain letters, digits and _"))

    fun tableName(value: String): String =
        value.takeIf { TABLE_NAME.matches(it) }
            ?: throw InvalidData(extras = mapOf("message" to "The table name may only contain letters, digits and _"))

    /**
     * SQL in an uploaded H2 database that H2 runs or that reaches outside the file when the database
     * is opened or read: triggers, Java functions, views, linked tables and file functions. A
     * LuckPerms export holds plain tables only, so a file with any of these is refused unopened.
     */
    private val UNSAFE_H2_SQL = listOf(
        "CREATE TRIGGER", "CREATE FORCE TRIGGER", "CREATE ALIAS", "CREATE FORCE ALIAS",
        "CREATE VIEW", "CREATE FORCE VIEW", "CREATE OR REPLACE", "CREATE LINKED TABLE", "CREATE FORCE LINKED TABLE",
        "CREATE AGGREGATE", "CREATE FORCE AGGREGATE", "CREATE DOMAIN", "LINK_SCHEMA", "FILE_WRITE", "FILE_READ", "CSVWRITE", "CSVREAD",
        "RUNSCRIPT", "JAVA_OBJECT", "AS $$"
    ).map { it.toByteArray(Charsets.US_ASCII) }

    /** True when [file] holds none of [UNSAFE_H2_SQL] (case-insensitive, also as UTF-16 text). */
    fun isPlainH2Export(file: File): Boolean {
        val bytes = file.readBytes()
        val upper = ByteArray(bytes.size) { i ->
            val b = bytes[i]
            if (b in 'a'.code.toByte()..'z'.code.toByte()) (b - 32).toByte() else b
        }

        return UNSAFE_H2_SQL.none { pattern -> contains(upper, pattern, 1) || contains(upper, pattern, 2) }
    }

    /** [pattern] at a stride of [step] bytes (2 = UTF-16 text, whichever byte order). */
    private fun contains(haystack: ByteArray, pattern: ByteArray, step: Int): Boolean {
        val span = (pattern.size - 1) * step + 1
        if (haystack.size < span) return false

        for (start in 0..haystack.size - span) {
            var match = true
            for (j in pattern.indices) {
                if (haystack[start + j * step] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }

        return false
    }
}
