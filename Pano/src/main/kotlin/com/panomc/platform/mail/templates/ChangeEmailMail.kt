package com.panomc.platform.mail.templates

import com.panomc.platform.mail.Mail
import io.vertx.core.json.JsonObject

class ChangeEmailMail(private val token: String) : Mail {
    override val templatePath = "mail/change-email.hbs"
    override val subject = "Pano - Verify E-mail Change"

    override suspend fun generateParameters(
        uiAddress: String,
    ): JsonObject {
        val parameters = JsonObject()

        parameters.put("link", "$uiAddress/activate-new-email?token=$token")

        return parameters
    }
}