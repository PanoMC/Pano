package com.panomc.platform.mail.templates

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.TemplateMail

/** Core mail `activation`: confirms a new account's e-mail address. */
class ActivationMail(
    private val token: String,
    private val username: String,
    private val email: String,
    private val activationCode: String
) : TemplateMail(OWNER, "activation") {
    override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> =
        mapOf(
            "username" to username,
            "email" to email,
            "activationLink" to activationLink(system),
            "activationCode" to activationCode,
            "expiresInMinutes" to EXPIRES_IN_MINUTES
        )

    /** Where the visitor confirms the account: target `auth.activate` of the front-end URL map. */
    internal fun activationLink(systemParameters: MailManager.Companion.SystemParameters) =
        systemParameters.linkTo("auth.activate", mapOf("token" to token), "/activate?token=$token")

    companion object {
        const val OWNER = "core"
        const val EXPIRES_IN_MINUTES = 15
    }
}
