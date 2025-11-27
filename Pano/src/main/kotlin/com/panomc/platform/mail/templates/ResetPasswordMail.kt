package com.panomc.platform.mail.templates

import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters

class ResetPasswordMail(private val token: String, private val resetCode: String) : Mail {
    override val templatePath = "mail/reset-password.hbs"
    override val subject = "mail.reset-password.subject"

    override suspend fun generateParameters(
        systemParameters: MailManager.Companion.SystemParameters,
        i18nManager: I18nManager,
        locale: String,
    ) = ResetPasswordMailParameters(
        "${systemParameters.websiteUrl}/renew-password?token=$token",
        resetCode,
        getTranslations(
            i18nManager, locale, "mail.reset-password", mapOf("reset-password-expires" to mapOf("minutes" to 30))
        )
    )

    companion object {
        data class ResetPasswordMailParameters(
            val resetLink: String,
            val resetCode: String,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}