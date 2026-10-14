package com.panomc.platform.mail.template

import com.panomc.platform.AppConstants
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** A `mail-templates/index.json`: the mails an owner (core or a plugin) declares. */
data class MailTemplateIndex(val mails: List<MailDefinition>) {
    companion object {
        val KEY_PATTERN = Regex("^[a-z0-9-]+$")

        /**
         * Parses an index. A mail or variant with an invalid key, or a duplicate, is an error: the whole
         * index is refused rather than half of it registered.
         *
         * @throws IllegalArgumentException on an invalid index
         */
        fun parse(json: JsonObject): MailTemplateIndex {
            val mails = (json.getValue("mails") as? JsonArray ?: JsonArray()).map { mail ->
                require(mail is JsonObject) { "Every entry of 'mails' must be an object" }

                MailDefinition.parse(mail)
            }

            val duplicate = mails.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.key

            require(duplicate == null) { "Mail '$duplicate' is declared twice" }

            return MailTemplateIndex(mails)
        }

        /** [map] in [locale], else en-US, else [fallback]. */
        fun localized(map: Map<String, String>, locale: String, fallback: String): String =
            map[locale]?.takeIf { it.isNotBlank() }
                ?: map[AppConstants.DEFAULT_LOCALE_CODE]?.takeIf { it.isNotBlank() }
                ?: fallback

        internal fun localizedMap(json: JsonObject, field: String): Map<String, String> {
            return when (val value = json.getValue(field)) {
                is JsonObject -> value.map.mapValues { it.value?.toString().orEmpty() }
                is String -> mapOf(AppConstants.DEFAULT_LOCALE_CODE to value)
                else -> emptyMap()
            }
        }
    }
}

data class MailDefinition(
    val key: String,
    val name: Map<String, String>,
    val description: Map<String, String>,
    val variants: List<VariantDefinition>
) {
    fun variant(key: String): VariantDefinition? = variants.firstOrNull { it.key == key }

    companion object {
        fun parse(json: JsonObject): MailDefinition {
            val key = json.getString("key").orEmpty()

            require(MailTemplateIndex.KEY_PATTERN.matches(key)) { "Invalid mail key '$key'" }

            val variants = (json.getValue("variants") as? JsonArray ?: JsonArray()).map { variant ->
                require(variant is JsonObject) { "Every variant of mail '$key' must be an object" }

                VariantDefinition.parse(variant)
            }.ifEmpty { listOf(VariantDefinition(VariantDefinition.DEFAULT, emptyMap(), emptyList())) }

            val duplicate = variants.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.key

            require(duplicate == null) { "Variant '$duplicate' of mail '$key' is declared twice" }

            return MailDefinition(
                key,
                MailTemplateIndex.localizedMap(json, "name"),
                MailTemplateIndex.localizedMap(json, "description"),
                variants
            )
        }
    }
}

data class VariantDefinition(
    val key: String,
    val name: Map<String, String>,
    val variables: List<VariableDefinition>
) {
    /** The declared variables with their samples, what previews and validation render with. */
    fun sampleVariables(): Map<String, Any?> = variables.associate { it.name to it.sampleValue() }

    companion object {
        const val DEFAULT = "default"

        fun parse(json: JsonObject): VariantDefinition {
            val key = json.getString("key") ?: DEFAULT

            require(MailTemplateIndex.KEY_PATTERN.matches(key)) { "Invalid variant key '$key'" }

            val variables = (json.getValue("variables") as? JsonArray ?: JsonArray()).map { variable ->
                require(variable is JsonObject) { "Every variable of variant '$key' must be an object" }

                VariableDefinition.parse(variable)
            }

            return VariantDefinition(key, MailTemplateIndex.localizedMap(json, "name"), variables)
        }
    }
}

/**
 * One variable a variant's templates may use. Plain types (`text | url | number | boolean | html`) carry one value;
 * a `list` carries rows (`{{#each name}}…{{/each}}`) whose [fields] are declared like plain variables and whose
 * [sample] is a JSON array of objects.
 */
data class VariableDefinition(
    val name: String,
    val type: Type,
    val label: Map<String, String>,
    val sample: Any?,
    /** The fields of one row of a `list` variable; empty for the plain types. */
    val fields: List<VariableDefinition> = emptyList()
) {
    enum class Type(val id: String) {
        TEXT("text"), URL("url"), NUMBER("number"), BOOLEAN("boolean"), HTML("html"), LIST("list");

        companion object {
            fun parse(id: String?): Type = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TEXT
        }
    }

    /**
     * [sample] as the type expects it; a missing boolean sample is true so `{{#if}}` blocks show in previews. A list
     * sample becomes a list of plain maps with every declared field in each row (a field a row leaves out is `false`
     * or `""`, never null: Handlebars would look a null up in the outer context and find a variable of the same name).
     */
    fun sampleValue(): Any? = when (type) {
        Type.BOOLEAN -> when (sample) {
            null -> true
            is Boolean -> sample
            else -> sample.toString().equals("true", ignoreCase = true)
        }

        Type.LIST -> listRows(sample)

        else -> sample ?: ""
    }

    private fun listRows(value: Any?): List<Map<String, Any?>> {
        val rows = when (value) {
            is JsonArray -> value.toList()
            is List<*> -> value
            else -> emptyList()
        }

        return rows.mapNotNull { row ->
            val map = when (row) {
                is JsonObject -> row.map
                is Map<*, *> -> row
                else -> return@mapNotNull null
            }

            LinkedHashMap<String, Any?>().apply {
                fields.forEach { field ->
                    put(field.name, if (map.containsKey(field.name) && map[field.name] != null) plain(map[field.name]) else field.emptyValue())
                }
                map.forEach { (key, item) -> if (key is String && !containsKey(key)) put(key, plain(item)) }
            }
        }
    }

    /** What a row field a sample row leaves out reads as. */
    private fun emptyValue(): Any = if (type == Type.BOOLEAN) false else ""

    fun toMap(locale: String): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "type" to type.id,
        "label" to MailTemplateIndex.localized(label, locale, name),
        "sample" to if (type == Type.LIST) sampleValue() else sample
    ).apply {
        if (type == Type.LIST) put("fields", fields.map { it.toMap(locale) })
    }

    companion object {
        private val NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        private fun plain(value: Any?): Any? = when (value) {
            is JsonObject -> value.map.mapValues { plain(it.value) }
            is JsonArray -> value.map { plain(it) }
            is Map<*, *> -> value.entries.associate { it.key.toString() to plain(it.value) }
            is List<*> -> value.map { plain(it) }
            else -> value
        }

        /**
         * Parses one variable. A `list` declares its row fields in `fields` (plain types only, no nested lists);
         * a field of a plain variable is ignored.
         */
        fun parse(json: JsonObject, allowList: Boolean = true): VariableDefinition {
            val name = json.getString("name").orEmpty()

            require(NAME_PATTERN.matches(name)) { "Invalid variable name '$name'" }

            val type = Type.parse(json.getString("type"))

            require(allowList || type != Type.LIST) { "Field '$name' of a list cannot be a list itself" }

            val fields = if (type == Type.LIST) {
                (json.getValue("fields") as? JsonArray ?: JsonArray()).map { field ->
                    require(field is JsonObject) { "Every field of list '$name' must be an object" }

                    parse(field, allowList = false)
                }
            } else emptyList()

            val duplicate = fields.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.key

            require(duplicate == null) { "Field '$duplicate' of list '$name' is declared twice" }

            return VariableDefinition(
                name,
                type,
                MailTemplateIndex.localizedMap(json, "label"),
                json.getValue("sample"),
                fields
            )
        }
    }
}
