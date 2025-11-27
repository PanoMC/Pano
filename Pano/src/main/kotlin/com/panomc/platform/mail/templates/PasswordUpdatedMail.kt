package com.panomc.platform.mail.templates

import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters

class PasswordUpdatedMail(private val username: String) : Mail {
    override val templatePath = "mail/password-updated.hbs"
    override val subject = "mail.password-updated.subject"

    override suspend fun generateParameters(
        systemParameters: MailManager.Companion.SystemParameters,
        i18nManager: I18nManager,
        locale: String,
    ) = PasswordUpdatedMailParameters(
        username,
        systemParameters.websiteUrl,
        getTranslations(
            i18nManager, locale, "mail.password-updated"
        )
    )

    companion object {
        data class PasswordUpdatedMailParameters(
            val username: String,
            val loginLİnk: String,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}