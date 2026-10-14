package com.panomc.platform.mail.templates

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.TemplateMail

/** Core mail `reset-password`: the link (and optional code) to set a new password. */
class ResetPasswordMail(private val token: String, private val resetCode: String) :
    TemplateMail(ActivationMail.OWNER, "reset-password") {
    override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> =
        mapOf(
            "resetLink" to resetLink(system),
            "resetCode" to resetCode,
            "expiresInMinutes" to EXPIRES_IN_MINUTES
        )

    /** Where the visitor sets a new password: target `auth.renew-password` of the front-end URL map. */
    internal fun resetLink(systemParameters: MailManager.Companion.SystemParameters) =
        systemParameters.linkTo("auth.renew-password", mapOf("token" to token), "/renew-password?token=$token")

    companion object {
        const val EXPIRES_IN_MINUTES = 30
    }
}
