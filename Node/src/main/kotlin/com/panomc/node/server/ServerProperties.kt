package com.panomc.node.server

import java.io.File
import java.util.Properties

/**
 * Reads and rewrites a server's `server.properties`.
 *
 * Merged rather than regenerated on purpose: by the time Pano changes a port or a MOTD the file
 * usually holds dozens of settings an operator or a plugin put there, and a node that rewrote it
 * from a template would silently undo all of them. Only the keys Pano actually sent are touched.
 *
 * Written by hand instead of through [Properties.store] because that escapes non-ASCII into
 * `\\uXXXX` and stamps a date comment on every save, which turns every MOTD in a language with
 * accents into mojibake in the panel's file editor.
 */
object ServerProperties {
    fun read(file: File): MutableMap<String, String> {
        if (!file.isFile) {
            return LinkedHashMap()
        }

        val result = LinkedHashMap<String, String>()

        file.readLines().forEach { line ->
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                return@forEach
            }

            val separator = trimmed.indexOf('=')

            if (separator <= 0) {
                return@forEach
            }

            result[trimmed.substring(0, separator).trim()] = trimmed.substring(separator + 1).trim()
        }

        return result
    }

    /** Applies [changes] onto the file, creating it when the install has not written one yet. */
    fun merge(file: File, changes: Map<String, String>) {
        if (changes.isEmpty() && file.isFile) {
            return
        }

        val merged = read(file)

        changes.forEach { (key, value) ->
            val cleanKey = key.trim()

            if (cleanKey.isNotEmpty() && isSafeKey(cleanKey)) {
                merged[cleanKey] = sanitiseValue(value)
            }
        }

        file.parentFile?.mkdirs()

        file.writeText(
            buildString {
                append("#Minecraft server properties\n")
                append("#Managed by Pano\n")

                merged.forEach { (key, value) ->
                    append(key)
                    append('=')
                    append(value)
                    append('\n')
                }
            }
        )
    }

    /** A key must be one plain token; anything else could inject a second setting. */
    internal fun isSafeKey(key: String) = key.none { it == '=' || it == '\n' || it == '\r' || it == ':' }

    /** A value may not carry a newline, for the same reason. */
    internal fun sanitiseValue(value: String) = value.replace("\r", "").replace("\n", " ")
}
