package com.panomc.platform.server.console

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.panomc.platform.server.dto.ConsoleSpan
import io.vertx.core.json.JsonArray
import java.lang.reflect.Type

/**
 * The colour spans of a console line, as Pano accepts them from a peer and hands them on (§2.4.21 D).
 *
 * Every span Pano relays is re-validated, whatever sent it: a node or a plugin is a peer, not a
 * trusted part of the platform, and what arrives here ends up in the panel's rendering code. A span
 * survives only when its offsets are integers inside the line (`0 ≤ start < end ≤ m.length`), its
 * colour is null or exactly `#rrggbb` in lower case, its flags are 0–7, it does not overlap or
 * precede the span before it, and it is among the first [MAX_SPANS]. Anything else is dropped on its
 * own — a bad span costs that span, never the line, and a line from an older peer simply has none.
 */
object ConsoleSpans {
    /** Most spans kept on one line, the same cap both parsers apply. */
    const val MAX_SPANS = 64

    /** Highest flags value: bold, italic and underline together. */
    const val MAX_FLAGS = 7

    /** The only colour shape relayed: never a raw string that could end up in a style attribute. */
    val COLOR = Regex("^#[0-9a-f]{6}$")

    /**
     * How many entries of one line's `c` are looked at before the rest is ignored.
     *
     * A conforming peer sends at most [MAX_SPANS]; this only bounds the work a hostile one can buy.
     */
    private const val MAX_ENTRIES_EXAMINED = MAX_SPANS * 4

    /**
     * The structurally sound spans out of whatever a peer sent as `c`, or null when it is not a list.
     *
     * Takes a Vert.x or Gson JSON array or a plain list. An entry is kept only when it is a
     * four-element array of integer, integer, string-or-null, integer; everything about the values
     * themselves is left to [validate], which needs the line's text to judge them.
     */
    fun parse(raw: Any?): List<ConsoleSpan>? {
        val entries = toList(raw) ?: return null

        return entries.asSequence()
            .take(MAX_ENTRIES_EXAMINED)
            .mapNotNull { entry -> toSpan(entry) }
            .toList()
    }

    /**
     * The spans of [spans] that are valid for a line whose text is [message], in order, or null when
     * none are.
     */
    fun validate(spans: List<ConsoleSpan>?, message: String): List<ConsoleSpan>? {
        if (spans.isNullOrEmpty()) {
            return null
        }

        val kept = ArrayList<ConsoleSpan>()

        var previousEnd = 0

        for (span in spans) {
            if (kept.size >= MAX_SPANS) {
                break
            }

            val inside = span.start >= 0 && span.start < span.end && span.end <= message.length
            val ordered = span.start >= previousEnd
            val colour = span.color == null || COLOR.matches(span.color)
            val flags = span.flags in 0..MAX_FLAGS

            if (inside && ordered && colour && flags) {
                kept.add(span)

                previousEnd = span.end
            }
        }

        return kept.takeIf { it.isNotEmpty() }
    }

    /** The wire form, `[[start, end, color, flags], …]`, with a JSON null for the default colour. */
    fun toJson(spans: List<ConsoleSpan>): JsonArray {
        val array = JsonArray()

        spans.forEach { span ->
            val entry = JsonArray().add(span.start).add(span.end)

            if (span.color == null) entry.addNull() else entry.add(span.color)

            array.add(entry.add(span.flags))
        }

        return array
    }

    private fun toSpan(entry: Any?): ConsoleSpan? {
        val values = toList(entry)?.takeIf { it.size == 4 } ?: return null

        val start = integer(values[0]) ?: return null
        val end = integer(values[1]) ?: return null
        val flags = integer(values[3]) ?: return null

        val color = when (val value = values[2]) {
            null -> null
            is String -> value
            else -> return null
        }

        return ConsoleSpan(start, end, color, flags)
    }

    /** A JSON array as a plain list of its values, nested arrays left as arrays; null otherwise. */
    private fun toList(raw: Any?): List<Any?>? = when (raw) {
        is JsonArray -> (0 until raw.size()).map { raw.getValue(it) }

        is com.google.gson.JsonArray -> raw.map { element -> fromGson(element) }

        is List<*> -> raw

        else -> null
    }

    private fun fromGson(element: JsonElement?): Any? = when {
        element == null || element.isJsonNull -> null

        element is JsonPrimitive && element.isNumber -> element.asNumber

        element is JsonPrimitive && element.isString -> element.asString

        element is JsonPrimitive -> element.asBoolean

        else -> element
    }

    /** An integral number within `Int`, or null — `3.5`, `"3"` and `1e12` are not offsets. */
    private fun integer(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null

        if (!number.isFinite() || number != Math.floor(number)) {
            return null
        }

        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            return null
        }

        return number.toInt()
    }

    /**
     * Gson's view of `c` on a decoded line.
     *
     * A field-level adapter rather than the default reflection, because the default would try to
     * read `[0, 3, "#ff7b72", 0]` as an object and fail the whole batch it arrived in — one bad
     * field from a peer must never cost the lines around it. Anything that is not an array decodes
     * to null; the values are judged later by [validate], once the line's text is known.
     */
    class GsonAdapter : JsonDeserializer<List<ConsoleSpan>?>, JsonSerializer<List<ConsoleSpan>?> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): List<ConsoleSpan>? = if (json != null && json.isJsonArray) parse(json.asJsonArray) else null

        override fun serialize(
            src: List<ConsoleSpan>?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement = if (src == null) JsonNull.INSTANCE else JsonParser.parseString(toJson(src).encode())
    }
}
