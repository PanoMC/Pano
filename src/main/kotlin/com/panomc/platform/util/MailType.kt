package com.panomc.platform.util

import com.panomc.platform.db.DatabaseManager
import io.vertx.core.json.JsonObject

enum class MailType(
    val templatePath: String,
    val subject: String,
    val parameterGenerator: suspend (
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        tokenProvider: TokenProvider
    ) -> JsonObject
) {
    ACTIVATION(
        templatePath = "mail/activation.hbs",
        subject = "Pano - Activate your e-mail",
        parameterGenerator = { _: String,
                               userId: Long,
                               uiAddress: String,
                               _: DatabaseManager,
                               tokenProvider: TokenProvider ->
            val parameters = JsonObject()

            val (token) = tokenProvider.generateToken(userId, TokenType.ACTIVATION)

            parameters.put("link", "$uiAddress/activate?token=$token")

            parameters
        }
    ),
}