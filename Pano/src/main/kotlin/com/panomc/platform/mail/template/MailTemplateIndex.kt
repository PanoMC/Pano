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

data class VariableDefinition(
    val name: String,
    val type: Type,
    val label: Map<String, String>,
    val sample: Any?
) {
    enum class Type(val id: String) {
        TEXT("text"), URL("url"), NUMBER("number"), BOOLEAN("boolean"), HTML("html");

        companion object {
            fun parse(id: String?): Type = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TEXT
        }
    }

    /** [sample] as the type expects it; a missing boolean sample is true so `{{#if}}` blocks show in previews. */
    fun sampleValue(): Any? = when (type) {
        Type.BOOLEAN -> when (sample) {
            null -> true
            is Boolean -> sample
            else -> sample.toString().equals("true", ignoreCase = true)
        }

        else -> sample ?: ""
    }

    fun toMap(locale: String): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "type" to type.id,
        "label" to MailTemplateIndex.localized(label, locale, name),
        "sample" to sample
    )

    companion object {
        private val NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun parse(json: JsonObject): VariableDefinition {
            val name = json.getString("name").orEmpty()

            require(NAME_PATTERN.matches(name)) { "Invalid variable name '$name'" }

            return VariableDefinition(
                name,
                Type.parse(json.getString("type")),
                MailTemplateIndex.localizedMap(json, "label"),
                json.getValue("sample")
            )
        }
    }
}
