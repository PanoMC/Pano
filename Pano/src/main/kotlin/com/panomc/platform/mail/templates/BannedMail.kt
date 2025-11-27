package com.panomc.platform.mail.templates

import com.panomc.platform.mail.Mail
import io.vertx.core.json.JsonObject

class BannedMail(private val username: String) : Mail {
    override val templatePath = "mail/banned.hbs"
    override val subject = "Pano - Your have been banned!"

    override suspend fun generateParameters(
        uiAddress: String,
    ): JsonObject {
        val parameters = JsonObject()

        parameters.put("username", username)

        return parameters
    }
}