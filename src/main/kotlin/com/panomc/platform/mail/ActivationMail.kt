package com.panomc.platform.mail

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.util.TokenProvider
import com.panomc.platform.util.TokenType
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection

class ActivationMail : Mail {
    override val templatePath = "mail/activation.hbs"
    override val subject = "Pano - Activate your e-mail"

    override suspend fun parameterGenerator(
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        tokenProvider: TokenProvider
    ): JsonObject {
        val parameters = JsonObject()

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.ACTIVATION, sqlConnection)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.ACTIVATION)

        tokenProvider.saveToken(token, userId.toString(), TokenType.ACTIVATION, expireDate, sqlConnection)

        parameters.put("link", "$uiAddress/activate?token=$token")

        return parameters
    }
}