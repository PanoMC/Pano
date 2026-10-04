package com.panomc.platform.server

import io.vertx.core.json.JsonObject

/**
 * The `server.properties` entries the panel may set, and how a request's values become the map
 * that is stored and forwarded.
 *
 * Any key the file itself could hold is accepted — the panel's Server properties page covers
 * the whole vanilla set and lets an operator add one it does not know — with two limits that are
 * about safety rather than policy: a key has to look like one (`level-name`, `rcon.port`), so it
 * can never smuggle a second line into the file, and [RESERVED] keys are refused because Pano
 * manages them elsewhere (`server-port` is the startup port, allocated and published by the
 * node, and a value written here would silently disagree with it).
 *
 * Unknown values are dropped rather than refused, because a newer panel sending something this
 * Pano cannot represent should not fail the whole request.
 */
object ServerPropertyKeys {
    /** Keys Pano manages through another setting; a request that names one is ignored for it. */
    val RESERVED = setOf("server-port")

    /**
     * The whitelist switch. Minecraft 26 writes `white-list=true` into a fresh `server.properties`
     * (1.21 wrote false), so a server Pano creates gets it written explicitly -- off unless the
     * create wizard's switch says otherwise.
     */
    const val WHITE_LIST = "white-list"

    /** One plain lower-case token, the way every vanilla key is spelled. */
    private val SAFE_KEY = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /** Longest value accepted; a motd or a resource-pack URL is the long one and this is generous. */
    const val MAX_VALUE_LENGTH = 1024

    /** Most keys one request may carry — the vanilla file has around sixty. */
    const val MAX_KEYS = 200

    /** @return whether [key] is one the panel may write. */
    fun isAllowed(key: String): Boolean = SAFE_KEY.matches(key) && key !in RESERVED

    /**
     * Turns a request body's `properties` object into the map that is stored and forwarded.
     *
     * Numbers and booleans are accepted and written as text because that is what the file holds,
     * and because a panel form has no way to know that `max-players` is a number and `motd` is
     * not. Nested objects and arrays are not values `server.properties` can express at all, so
     * they are dropped, as is any key that is not one the panel may write.
     */
    fun read(properties: JsonObject?): Map<String, String> {
        if (properties == null) {
            return emptyMap()
        }

        val result = linkedMapOf<String, String>()

        for (key in properties.fieldNames()) {
            if (result.size >= MAX_KEYS) {
                break
            }

            if (!isAllowed(key)) {
                continue
            }

            val value = properties.getValue(key) ?: continue

            val text = when (value) {
                is String -> value
                is Boolean -> value.toString()
                is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
                else -> continue
            }

            // A newline would end the line and start another key in the file it is written into.
            result[key] = text.replace(Regex("[\\r\\n]"), " ").take(MAX_VALUE_LENGTH)
        }

        return result
    }

    /**
     * The entries of a `server.properties` file, in file order.
     *
     * The same reading the node does before it merges Pano's changes in: comments and blank
     * lines are skipped, a line splits at its first `=`, and both halves are trimmed. Keys that
     * would not be accepted back ([isAllowed]) are still returned — the page shows the file as it
     * is, and only refuses to write what it may not.
     */
    fun parseFile(content: String?): Map<String, String> {
        if (content.isNullOrEmpty()) {
            return emptyMap()
        }

        val result = linkedMapOf<String, String>()

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                return@forEach
            }

            val separator = trimmed.indexOf('=')

            if (separator <= 0) {
                return@forEach
            }

            result[trimmed.substring(0, separator).trim()] = unescape(trimmed.substring(separator + 1).trim())
        }

        return result
    }

    /**
     * A value as `java.util.Properties` would read it: the server writes its file through that
     * class, which escapes `:`, `=`, `#`, `!` and the backslash itself, and writes non-ASCII as a
     * backslash-u escape with four hex digits. Left escaped, a world type such as
     * `minecraft` + backslash + `:normal` would not match any option the page lists.
     *
     * Only ever applied on the way in. On the way out the node writes the text as it is, and the
     * server's own reader accepts an unescaped `:` — it is the escaped form that is optional.
     */
    internal fun unescape(value: String): String {
        if (!value.contains('\\')) {
            return value
        }

        val out = StringBuilder(value.length)
        var i = 0

        while (i < value.length) {
            val c = value[i]

            if (c != '\\' || i == value.lastIndex) {
                out.append(c)
                i++

                continue
            }

            val next = value[i + 1]

            if (next == 'u' && i + 5 < value.length) {
                val code = value.substring(i + 2, i + 6).toIntOrNull(16)

                if (code != null) {
                    out.append(code.toChar())
                    i += 6

                    continue
                }
            }

            out.append(
                when (next) {
                    't' -> '\t'
                    'n' -> '\n'
                    'r' -> '\r'
                    'f' -> '\u000C'
                    else -> next
                }
            )
            i += 2
        }

        return out.toString()
    }

    fun toJsonObject(properties: Map<String, String>): JsonObject = JsonObject(properties.mapValues { it.value as Any })
}
