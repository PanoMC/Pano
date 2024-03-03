package com.panomc.platform.mail.mails

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

class ResetPasswordMail : Mail {
    override val templatePath = "mail/reset-password.hbs"
    override val subject = "Pano - Reset your password"

    override suspend fun parameterGenerator(
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlClient: SqlClient,
        tokenProvider: TokenProvider
    ): JsonObject {
        val parameters = JsonObject()

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.RESET_PASSWORD, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.RESET_PASSWORD)

        tokenProvider.saveToken(
            token,
            userId.toString(),
            TokenType.RESET_PASSWORD,
            expireDate,
            sqlClient
        )

        parameters.put("link", "$uiAddress/renew-password?token=$token")

        return parameters
    }
}