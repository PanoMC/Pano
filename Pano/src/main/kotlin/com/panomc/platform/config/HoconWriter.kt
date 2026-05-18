package com.panomc.platform.config

import com.google.gson.annotations.SerializedName
import com.panomc.platform.api.config.ConfigComment
import com.panomc.platform.api.config.ConfigSection
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Renders a [JsonObject] as HOCON, using a Kotlin data class as the schema for:
 *   - canonical key ordering (declared field order)
 *   - per-field comments via [ConfigComment]
 *   - section banners + blank-line separators via [ConfigSection]
 *
 * Replaces the previous `Gson.toJson() → ConfigFactory.parseString() → render()` round-trip
 * which discarded comments and any visual structure. Output is valid HOCON and round-trips
 * cleanly through Vert.x's hocon ConfigRetriever.
 *
 * Unknown keys present in [data] but missing from the schema class are emitted at the end so
 * forward-compat config values (e.g. mid-migration) don't get dropped.
 */
object HoconWriter {

    private const val INDENT_UNIT = "    "
    private const val BANNER = "# ============================================================"

    fun render(data: JsonObject, schemaClass: Class<*>): String {
        val sb = StringBuilder()
        renderObject(sb, data, schemaClass, indent = 0, rootScope = true)
        return sb.toString()
    }

    private fun renderObject(
        sb: StringBuilder,
        data: JsonObject,
        schemaClass: Class<*>,
        indent: Int,
        rootScope: Boolean
    ) {
        val fields = schemaFields(schemaClass)
        val knownKeys = mutableSetOf<String>()
        var firstEmit = true

        for (field in fields) {
            val key = configKeyOf(field)
            if (key in knownKeys) continue
            knownKeys += key
            if (!data.containsKey(key)) continue
            val value = data.getValue(key) ?: continue

            val section = field.getAnnotation(ConfigSection::class.java)
            val comment = field.getAnnotation(ConfigComment::class.java)

            if (section != null) {
                emitSectionBanner(sb, section.title, indent, leadingBlankLine = !firstEmit || !rootScope)
            } else if (!firstEmit) {
                // Blank line before each subsequent comment block at root, for breathing room.
                if (rootScope && comment != null) sb.append('\n')
            }

            if (comment != null) {
                for (line in comment.lines) {
                    appendIndent(sb, indent)
                    sb.append("# ").append(line).append('\n')
                }
            }

            emitKeyValue(sb, key, value, field.type, indent)
            firstEmit = false
        }

        // Forward-compat: keys in data without a schema field. No comments, no section.
        for (key in data.fieldNames()) {
            if (key in knownKeys) continue
            val value = data.getValue(key) ?: continue
            emitKeyValue(sb, key, value, value.javaClass, indent)
        }
    }

    private fun emitKeyValue(
        sb: StringBuilder,
        key: String,
        value: Any,
        fieldType: Class<*>,
        indent: Int
    ) {
        appendIndent(sb, indent)
        sb.append(formatKey(key))

        when (value) {
            is JsonObject -> {
                sb.append(" {\n")
                renderObject(sb, value, fieldType, indent + 1, rootScope = false)
                appendIndent(sb, indent)
                sb.append("}\n")
            }
            is JsonArray -> {
                sb.append(" = ")
                emitArray(sb, value, indent)
            }
            else -> {
                sb.append(" = ")
                sb.append(formatScalar(value))
                sb.append('\n')
            }
        }
    }

    private fun emitArray(sb: StringBuilder, array: JsonArray, indent: Int) {
        if (array.isEmpty) {
            sb.append("[]\n")
            return
        }
        sb.append("[\n")
        val inner = indent + 1
        for (i in 0 until array.size()) {
            val item = array.getValue(i)
            appendIndent(sb, inner)
            when (item) {
                is JsonObject -> {
                    sb.append("{\n")
                    renderObject(sb, item, Any::class.java, inner + 1, rootScope = false)
                    appendIndent(sb, inner)
                    sb.append("}")
                }
                is JsonArray -> {
                    // Nested arrays: render inline-flat (rare).
                    sb.append("[")
                    for (j in 0 until item.size()) {
                        if (j > 0) sb.append(", ")
                        sb.append(formatScalar(item.getValue(j)))
                    }
                    sb.append("]")
                }
                else -> sb.append(formatScalar(item))
            }
            if (i < array.size() - 1) sb.append(',')
            sb.append('\n')
        }
        appendIndent(sb, indent)
        sb.append("]\n")
    }

    private fun emitSectionBanner(sb: StringBuilder, title: String, indent: Int, leadingBlankLine: Boolean) {
        if (leadingBlankLine) sb.append('\n')
        appendIndent(sb, indent); sb.append(BANNER).append('\n')
        appendIndent(sb, indent); sb.append("# ").append(title).append('\n')
        appendIndent(sb, indent); sb.append(BANNER).append('\n')
    }

    private fun appendIndent(sb: StringBuilder, indent: Int) {
        repeat(indent) { sb.append(INDENT_UNIT) }
    }

    /**
     * Declared fields in source order, **superclass first**, then the class itself. Plugin configs
     * extend [com.panomc.platform.api.config.PluginConfig], so this puts the inherited `version`
     * field (and its [ConfigComment]) at the top of the file before the plugin's own fields. Skips
     * synthetic/static/companion fields.
     */
    private fun schemaFields(clazz: Class<*>): List<Field> {
        if (clazz == Any::class.java || clazz.isPrimitive) return emptyList()
        val chain = ArrayDeque<Class<*>>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            chain.addFirst(current)
            current = current.superclass
        }
        val result = mutableListOf<Field>()
        for (c in chain) {
            for (field in c.declaredFields) {
                val mods = field.modifiers
                if (Modifier.isStatic(mods) || field.isSynthetic) continue
                if (field.name == "Companion") continue
                result += field
            }
        }
        return result
    }

    private fun configKeyOf(field: Field): String {
        return field.getAnnotation(SerializedName::class.java)?.value ?: field.name
    }

    private val UNQUOTED_KEY = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    private fun formatKey(key: String): String =
        if (UNQUOTED_KEY.matches(key)) key else "\"" + escapeString(key) + "\""

    private fun formatScalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is CharSequence -> "\"" + escapeString(value.toString()) + "\""
        else -> "\"" + escapeString(value.toString()) + "\""
    }

    private fun escapeString(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
