package com.panomc.platform.mail.templates

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.TemplateMail

/** Core mail `password-updated`: tells the user their password was changed. */
class PasswordUpdatedMail(private val username: String) : TemplateMail(ActivationMail.OWNER, "password-updated") {
    override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> =
        mapOf(
            "username" to username,
            "loginLink" to system.linkTo("auth.login", emptyMap(), "/login")
        )
}
