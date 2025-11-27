package com.panomc.platform.mail.templates

import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters

class ActivationMail(
    private val token: String,
    private val username: String,
    private val email: String,
    private val activationCode: String
) : Mail {
    override val templatePath = "mail/activate-email.hbs"
    override val subject = "mail.activation.subject"

    override suspend fun generateParameters(systemParameters: MailManager.Companion.SystemParameters, i18nManager: I18nManager, locale: String) =
        ActivationMailParameters(
            "${systemParameters.websiteUrl}/activate?token=$token",
            username,
            email,
            activationCode,
            getTranslations(
                i18nManager, locale, "mail.activation", mapOf("activation-expires" to mapOf("minutes" to 15))
            )
        )

    companion object {
        data class ActivationMailParameters(
            val activationLink: String,
            val username: String,
            val email: String,
            val activationCode: String,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}