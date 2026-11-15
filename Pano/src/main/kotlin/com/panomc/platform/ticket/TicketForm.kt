package com.panomc.platform.ticket

import com.panomc.platform.error.InvalidFields
import io.vertx.core.json.JsonObject

/**
 * What the "new ticket" form asks for besides the category's custom fields: whether it shows the title and the
 * description (the ticket's first message) and the hint text inside each. A category keeps its own (column `form`),
 * tickets without a category use the one of the ticket settings. An empty placeholder means the theme's default.
 */
data class TicketForm(
    val titleEnabled: Boolean = true,
    val titlePlaceholder: String = "",
    val messageEnabled: Boolean = true,
    val messagePlaceholder: String = ""
) {
    fun toJson(): JsonObject = JsonObject()
        .put("titleEnabled", titleEnabled)
        .put("titlePlaceholder", titlePlaceholder)
        .put("messageEnabled", messageEnabled)
        .put("messagePlaceholder", messagePlaceholder)

    fun encode(): String = toJson().encode()

    companion object {
        const val PLACEHOLDER_MAX = 200

        /** Title of a ticket opened without one: at most this many characters of its description. */
        const val DERIVED_TITLE_MAX = 60

        /** The stored form; a missing or broken value is the default form (everything shown). */
        fun decode(raw: String?): TicketForm {
            if (raw.isNullOrBlank()) return TicketForm()

            val json = try {
                JsonObject(raw)
            } catch (e: Exception) {
                return TicketForm()
            }

            return fromJson(json)
        }

        private fun fromJson(json: JsonObject) = TicketForm(
            titleEnabled = json.getValue("titleEnabled") as? Boolean ?: true,
            titlePlaceholder = (json.getValue("titlePlaceholder") as? String).orEmpty(),
            messageEnabled = json.getValue("messageEnabled") as? Boolean ?: true,
            messagePlaceholder = (json.getValue("messagePlaceholder") as? String).orEmpty()
        )

        /**
         * The `form` of a save: missing is [fallback]; a placeholder that is not a string or too long throws
         * [InvalidFields] with `form.<prop>` keys.
         */
        fun parse(raw: Any?, fallback: TicketForm = TicketForm()): TicketForm {
            if (raw == null) return fallback

            val json = raw as? JsonObject ?: throw InvalidFields(mapOf("form" to true))
            val errors = mutableMapOf<String, Boolean>()

            fun placeholder(key: String, current: String): String {
                if (!json.containsKey(key)) return current

                val text = (json.getValue(key) as? String)?.trim()

                if (text == null || text.length > PLACEHOLDER_MAX) {
                    errors["form.$key"] = true

                    return current
                }

                return text
            }

            fun flag(key: String, current: Boolean): Boolean {
                if (!json.containsKey(key)) return current

                return json.getValue(key) as? Boolean ?: run {
                    errors["form.$key"] = true

                    current
                }
            }

            val form = TicketForm(
                titleEnabled = flag("titleEnabled", fallback.titleEnabled),
                titlePlaceholder = placeholder("titlePlaceholder", fallback.titlePlaceholder),
                messageEnabled = flag("messageEnabled", fallback.messageEnabled),
                messagePlaceholder = placeholder("messagePlaceholder", fallback.messagePlaceholder)
            )

            if (errors.isNotEmpty()) throw InvalidFields(errors)

            return form
        }

        /**
         * The title of a ticket opened with the title turned off: the start of its description on one line, else of
         * its first filled custom field, else the category's title; null when none is there (the caller then names it
         * after its id).
         */
        fun derivedTitle(message: String?, firstFieldText: String?, categoryTitle: String?): String? {
            for (text in listOf(message, firstFieldText)) {
                val line = oneLine(text)

                if (line.isNotEmpty()) {
                    return if (line.length <= DERIVED_TITLE_MAX) line
                    else line.take(DERIVED_TITLE_MAX - 1).trimEnd() + "…"
                }
            }

            return categoryTitle?.takeIf { it.isNotBlank() && it != "-" }
        }

        private fun oneLine(text: String?) = text
            ?.replace(Regex("<[^>]*>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }
}
