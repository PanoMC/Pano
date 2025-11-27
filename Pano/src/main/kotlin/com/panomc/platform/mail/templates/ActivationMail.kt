package com.panomc.platform.mail.templates

import com.panomc.platform.mail.Mail
import io.vertx.core.json.JsonObject

class ActivationMail(private val token: String) : Mail {
    override val templatePath = "mail/activation.hbs"
    override val subject = "Pano - Activate your e-mail"

    override suspend fun generateParameters(uiAddress: String): JsonObject {
        val parameters = JsonObject()

        parameters.put("link", "$uiAddress/activate?token=$token")

        return parameters
    }
}