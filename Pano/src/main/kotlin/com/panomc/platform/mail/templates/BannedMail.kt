package com.panomc.platform.mail.templates

import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BannedMail(private val username: String, private val reason: String?, private val bannedUntil: Long?) : Mail {
    override val templatePath = "mail/banned.hbs"
    override val subject = "mail.banned.subject"

    override suspend fun generateParameters(
        systemParameters: MailManager.Companion.SystemParameters,
        i18nManager: I18nManager,
        locale: String,
    ): MailParameters {
        val formattedBannedUntil = bannedUntil?.let { timestamp ->
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()
            )
            dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }

        return BannedMailParameters(
            username,
            reason,
            formattedBannedUntil,
            getTranslations(
                i18nManager, locale, "mail.banned"
            )
        )
    }

    companion object {
        data class BannedMailParameters(
            val username: String,
            val reason: String?,
            val bannedUntil: String?,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}