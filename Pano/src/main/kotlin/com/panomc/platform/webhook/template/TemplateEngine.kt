package com.panomc.platform.webhook.template

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject

/**
 * The template language of webhook messages. Pure: no I/O.
 *
 * A template is the JSON body a chat service takes (Discord, Slack, Telegram, Teams, or any JSON for a custom
 * endpoint) with tokens: `{name}` or `{name|fallback}`. `name` is a dotted path such as `data.username`, `site.name`
 * or `event.iso`; the caller gives the values. A token is replaced only when its name is known (in the value map, or
 * under `data.`), so any other `{...}` text stays as it is.
 *
 * - Inside a JSON string the value is first escaped for the service ([MessageFormat.escapeValue], e.g. `<` for Slack
 *   and Telegram, so a player's text can never become a mention or markup) and then JSON-escaped, so it can never
 *   break out of its string.
 * - Outside a string only a whole JSON value is written as it is (`{json.data}` gives the data object, `{event.color}`
 *   a number); anything else becomes `0`.
 */
object TemplateEngine {
    const val ELLIPSIS = "\u2026"

    /** `{name}` or `{name|fallback}`; the name starts with a lower-case letter. */
    private val TOKEN = Regex("""\{([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*)(?:\|([^{}"\\]{0,64}))?\}""")

    /** Names that are tokens even without a value: every path under `data.` (a missing field is empty). */
    private fun isDynamic(name: String) = name.startsWith("data.")

    /** The finished body of [template] for [format], or `null` when it gives no usable message. */
    fun render(template: String, vars: Map<String, String>, format: MessageFormat): JsonObject? {
        val parsed = parse(substitute(template, vars, format::escapeValue)) ?: return null

        return format.finish(parsed)
    }

    private fun parse(text: String): JsonObject? = try {
        JsonObject(text)
    } catch (e: Exception) {
        null
    }

    /** One pass over [template]: known tokens become their value, escaped for the place they stand in. */
    fun substitute(template: String, vars: Map<String, String>, escapeValue: (String) -> String = { it }): String {
        val out = StringBuilder(template.length + 128)
        var inString = false
        var escaped = false
        var i = 0

        while (i < template.length) {
            val c = template[i]

            if (c == '{') {
                val match = TOKEN.matchAt(template, i)
                val name = match?.groupValues?.get(1)

                if (match != null && name != null && (name in vars || isDynamic(name))) {
                    val raw = vars[name].orEmpty().ifEmpty { match.groups[2]?.value.orEmpty() }

                    out.append(if (inString) escapeJson(escapeValue(raw)) else rawJson(raw))
                    i = match.range.last + 1
                    escaped = false

                    continue
                }
            }

            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else if (c == '"') {
                inString = true
            }

            out.append(c)
            i++
        }

        return out.toString()
    }

    /** [raw] when it is one whole JSON value (object, array, string, number, boolean, null), else `0`. */
    private fun rawJson(raw: String): String {
        val text = raw.trim()

        if (text.isEmpty()) return "0"

        return try {
            Json.decodeValue(text)

            text
        } catch (e: Exception) {
            "0"
        }
    }

    /** The inside of a JSON string for [text]: quotes, backslashes, control characters and the line separators are escaped. */
    fun escapeJson(text: String): String {
        val encoded = Json.encode(text)

        return encoded.substring(1, encoded.length - 1).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }

    /** `&`, `<` and `>` as HTML entities: what Slack and Telegram's HTML mode need around user text. */
    fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** [text] cut to [max] characters, the last one being the ellipsis; a surrogate pair is never split. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text

        var end = max - 1

        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--

        return text.substring(0, end) + ELLIPSIS
    }

    /**
     * `null` when [template] is usable for [format], else the field error code: `INVALID_JSON` when it is no JSON
     * object once every token is filled in, `EMPTY` when the service would get no message from it.
     */
    fun validate(template: String, format: MessageFormat): String? {
        val probes = TOKEN.findAll(template).map { it.groupValues[1] }.associateWith { "1" }
        val parsed = parse(substitute(template, probes)) ?: return "INVALID_JSON"

        return if (format.finish(parsed) != null) null else "EMPTY"
    }

    /** The token names [template] uses, in order of first use. */
    fun tokensOf(template: String): List<String> = TOKEN.findAll(template).map { it.groupValues[1] }.distinct().toList()
}
