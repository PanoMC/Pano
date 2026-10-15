package com.panomc.platform.mail

import com.panomc.platform.util.Regexes

/**
 * Per-message overrides for [MailManager.sendMail].
 *
 * Every field is optional; the companion holds the pure helpers `sendMail` applies to them so the rules
 * (locale precedence, subject / Reply-To sanitising, attachment limits) are testable without SMTP.
 */
class MailOptions(
    /** Recipient override (the former 4th argument of `sendMail`). */
    val email: String? = null,
    /** Overrides `user.localeCode` / `config.locale`. */
    val locale: String? = null,
    /** Pre-rendered subject; when non-null (and not blank after sanitising) the I18nManager lookup is skipped. */
    val subject: String? = null,
    /** Plain-text alternative (`MailMessage.text`). */
    val text: String? = null,
    /** `Reply-To` header when non-blank and a valid e-mail address. */
    val replyTo: String? = null,
    /** Regular attachments, added next to the inline logo. */
    val attachments: List<MailFile> = emptyList()
) {
    companion object {
        const val MAX_SUBJECT_LENGTH = 255
        const val MAX_ATTACHMENTS = 5
        const val MAX_ATTACHMENTS_TOTAL_BYTES = 10 * 1024 * 1024
        const val MAX_FILE_NAME_LENGTH = 100
        const val DEFAULT_FILE_NAME = "attachment"

        private val contentTypeRegex = Regex("^[\\w.+-]+/[\\w.+-]+$")
        private val emailRegex = Regex(Regexes.EMAIL)
        private val unsafeFileNameChars = Regex("[^A-Za-z0-9._-]")

        /** options.locale (non-blank) > user locale > site locale. */
        fun resolveLocale(optionsLocale: String?, userLocale: String?, siteLocale: String): String =
            optionsLocale?.takeIf { it.isNotBlank() } ?: userLocale ?: siteLocale

        /** Recipient: options.email (non-blank) else the user's address; null when neither exists. */
        fun resolveRecipient(optionsEmail: String?, userEmail: String?): String? =
            optionsEmail?.takeIf { it.isNotBlank() } ?: userEmail

        /** Removes CR / LF, trims, cuts to 255 chars; a blank result is null (translation fallback applies). */
        fun sanitizeSubject(subject: String?): String? {
            if (subject == null) {
                return null
            }

            return subject
                .replace("\r", "")
                .replace("\n", "")
                .trim()
                .take(MAX_SUBJECT_LENGTH)
                .trim()
                .ifBlank { null }
        }

        /** Translated subject if any, else the website name (never a null subject). */
        inline fun resolveSubject(optionsSubject: String?, translated: () -> String?, websiteName: String): String =
            sanitizeSubject(optionsSubject) ?: translated() ?: websiteName

        /** Removes CR / LF, trims; returns the address only when it matches the e-mail regex, else null. */
        fun sanitizeReplyTo(replyTo: String?): String? {
            val cleaned = replyTo?.replace("\r", "")?.replace("\n", "")?.trim()

            return cleaned?.takeIf { it.isNotBlank() && it.matches(emailRegex) }
        }

        /** Non-blank text or null. */
        fun sanitizeText(text: String?): String? = text?.takeIf { it.isNotBlank() }

        /** Characters outside `[A-Za-z0-9._-]` become `_`, max 100 chars, blank becomes `attachment`. */
        fun sanitizeFileName(name: String): String {
            val sanitized = name.trim().replace(unsafeFileNameChars, "_").take(MAX_FILE_NAME_LENGTH)

            return sanitized.ifBlank { DEFAULT_FILE_NAME }
        }

        /**
         * Validates count, total size and content types and returns the files with sanitised names.
         * @throws IllegalArgumentException on any violation (before any SMTP work).
         */
        fun validateAttachments(attachments: List<MailFile>): List<MailFile> {
            require(attachments.size <= MAX_ATTACHMENTS) {
                "Too many attachments: ${attachments.size} (max $MAX_ATTACHMENTS)"
            }

            var total = 0L

            attachments.forEach { total += it.data.size }

            require(total <= MAX_ATTACHMENTS_TOTAL_BYTES) {
                "Attachments too large: $total bytes (max $MAX_ATTACHMENTS_TOTAL_BYTES)"
            }

            return attachments.map {
                require(it.contentType.matches(contentTypeRegex)) { "Invalid attachment content type" }

                MailFile(sanitizeFileName(it.name), it.contentType, it.data)
            }
        }
    }
}
