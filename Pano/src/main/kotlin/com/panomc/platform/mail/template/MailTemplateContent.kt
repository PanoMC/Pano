package com.panomc.platform.mail.template

/**
 * One mail × variant × language: the fields the panel edits and a bundled `<locale>.hbs` holds.
 *
 * Every field is a Handlebars template. In [Mode.FIELDS] the fields are rendered and poured into the
 * shared layout; in [Mode.HTML] [body] is a complete HTML document rendered without the layout.
 */
data class MailTemplateContent(
    val mode: Mode = Mode.FIELDS,
    val subject: String,
    val preheader: String = "",
    val body: String,
    val buttonLabel: String = "",
    val buttonUrl: String = "",
    val footer: String = ""
) {
    enum class Mode {
        FIELDS, HTML;

        companion object {
            /** Case-insensitive; null for anything else. */
            fun parse(value: String?): Mode? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
        }
    }

    /** The panel's "template" object: nullable strings are "". */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode.name,
        "subject" to subject,
        "preheader" to preheader,
        "body" to body,
        "buttonLabel" to buttonLabel,
        "buttonUrl" to buttonUrl,
        "footer" to footer
    )

    companion object {
        const val FIELD_SUBJECT = "subject"
        const val FIELD_PREHEADER = "preheader"
        const val FIELD_BODY = "body"
        const val FIELD_BUTTON_LABEL = "buttonLabel"
        const val FIELD_BUTTON_URL = "buttonUrl"
        const val FIELD_FOOTER = "footer"

        private const val DELIMITER = "---"

        /**
         * Parses a bundled `<locale>.hbs`: a header of single-line `key: value` pairs between `---` lines,
         * then the body. Values are taken literally after `key: ` (no quoting). A file without a header is
         * all body with an empty subject. Unknown header keys are ignored.
         *
         * @throws IllegalArgumentException when the header is opened but never closed, or `mode` is unknown
         */
        fun parse(source: String): MailTemplateContent {
            val text = source.removePrefix("﻿").replace("\r\n", "\n")
            val lines = text.split("\n")

            if (lines.firstOrNull()?.trim() != DELIMITER) {
                return MailTemplateContent(subject = "", body = text.trim())
            }

            val end = lines.drop(1).indexOfFirst { it.trim() == DELIMITER }

            require(end >= 0) { "The front-matter header is not closed with '$DELIMITER'" }

            val header = linkedMapOf<String, String>()

            lines.subList(1, end + 1).forEach { line ->
                if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach

                val colon = line.indexOf(':')

                require(colon > 0) { "Header line without 'key: value': $line" }

                header[line.substring(0, colon).trim()] = line.substring(colon + 1).removePrefix(" ").trimEnd()
            }

            val body = lines.drop(end + 2).joinToString("\n").trim()

            val mode = header["mode"]?.let {
                Mode.parse(it) ?: throw IllegalArgumentException("Unknown mode '$it'")
            } ?: Mode.FIELDS

            return MailTemplateContent(
                mode = mode,
                subject = header[FIELD_SUBJECT].orEmpty(),
                preheader = header[FIELD_PREHEADER].orEmpty(),
                body = body,
                buttonLabel = header[FIELD_BUTTON_LABEL].orEmpty(),
                buttonUrl = header[FIELD_BUTTON_URL].orEmpty(),
                footer = header[FIELD_FOOTER].orEmpty()
            )
        }
    }
}
