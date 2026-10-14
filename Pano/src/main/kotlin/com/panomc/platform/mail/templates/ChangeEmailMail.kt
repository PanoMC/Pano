package com.panomc.platform.mail.templates

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.TemplateMail

/** Core mail `change-email`: confirms the new address of an e-mail change. */
class ChangeEmailMail(private val token: String, private val username: String, private val newEmail: String) :
    TemplateMail(ActivationMail.OWNER, "change-email") {
    override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> =
        mapOf(
            "username" to username,
            "newEmail" to newEmail,
            "confirmationLink" to confirmationLink(system),
            "expiresInMinutes" to EXPIRES_IN_MINUTES
        )

    /** Where the visitor confirms the new address: target `auth.activate-new-email` of the front-end URL map. */
    internal fun confirmationLink(systemParameters: MailManager.Companion.SystemParameters) =
        systemParameters.linkTo("auth.activate-new-email", mapOf("token" to token), "/activate-new-email?token=$token")

    companion object {
        const val EXPIRES_IN_MINUTES = 15
    }
}
