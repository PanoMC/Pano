package com.panomc.platform.mail.templates

import com.panomc.platform.mail.Mail
import io.vertx.core.json.JsonObject

class ResetPasswordMail(private val token: String) : Mail {
    override val templatePath = "mail/reset-password.hbs"
    override val subject = "Pano - Reset your password"

    override suspend fun generateParameters(
        uiAddress: String,
    ): JsonObject {
        val parameters = JsonObject()

        parameters.put("link", "$uiAddress/renew-password?token=$token")

        return parameters
    }
}