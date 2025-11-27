package com.panomc.platform.mail.templates

import com.panomc.platform.mail.Mail
import io.vertx.core.json.JsonObject

class PasswordUpdatedMail(private val username: String) : Mail {
    override val templatePath = "mail/password-updated.hbs"
    override val subject = "Pano - Your password has been updated"

    override suspend fun generateParameters(
        uiAddress: String,
    ): JsonObject {
        val parameters = JsonObject()

        parameters.put("username", username)

        return parameters
    }
}