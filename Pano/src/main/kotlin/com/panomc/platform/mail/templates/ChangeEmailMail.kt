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
        confirmationLink(systemParameters),
        username,
        newEmail,
        getTranslations(
            i18nManager, locale, "mail.change-email", mapOf("change-email-expires" to mapOf("minutes" to 15))
        )
    )

    /** Where the visitor confirms the new address: target `auth.activate-new-email` of the front-end URL map. */
    internal fun confirmationLink(systemParameters: MailManager.Companion.SystemParameters) =
        systemParameters.linkTo("auth.activate-new-email", mapOf("token" to token), "/activate-new-email?token=$token")

    companion object {
        data class ChangeEmailMailParameters(
            val confirmationLink: String,
            val username: String,
            val newEmail: String,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}