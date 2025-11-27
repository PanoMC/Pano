package com.panomc.platform.mail.templates

import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters

class ChangeEmailMail(private val token: String, private val username: String, private val newEmail: String) : Mail {
    override val templatePath = "mail/change-email.hbs"
    override val subject = "mail.change-email.subject"

    override suspend fun generateParameters(
        systemParameters: MailManager.Companion.SystemParameters,
        i18nManager: I18nManager,
        locale: String,
    ) = ChangeEmailMailParameters(
        "${systemParameters.websiteUrl}/activate-new-email?token=$token",
        username,
        newEmail,
        getTranslations(
            i18nManager, locale, "mail.change-email", mapOf("change-email-expires" to mapOf("minutes" to 15))
        )
    )

    companion object {
        data class ChangeEmailMailParameters(
            val confirmationLink: String,
            val username: String,
            val newEmail: String,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}