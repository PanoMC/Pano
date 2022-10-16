package com.panomc.platform.util

import com.panomc.platform.db.DatabaseManager
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection

enum class MailType(
    val templatePath: String,
    val subject: String,
    val parameterGenerator: suspend (
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
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
                               sqlConnection: SqlConnection,
                               tokenProvider: TokenProvider ->
            val parameters = JsonObject()

            tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.ACTIVATION, sqlConnection)

            val (token, expireDate) = tokenProvider.generateToken(userId, TokenType.ACTIVATION)

            tokenProvider.saveToken(token, userId.toString(), TokenType.ACTIVATION, expireDate, sqlConnection)

            parameters.put("link", "$uiAddress/activate?token=$token")

            parameters
        }
    ),
    RESET_PASSWORD(
        templatePath = "mail/reset-password.hbs",
        subject = "Pano - Reset your password",
        parameterGenerator = { _: String,
                               userId: Long,
                               uiAddress: String,
                               _: DatabaseManager,
                               sqlConnection: SqlConnection,
                               tokenProvider: TokenProvider ->
            val parameters = JsonObject()

            tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.RESET_PASSWORD, sqlConnection)

            val (token, expireDate) = tokenProvider.generateToken(userId, TokenType.RESET_PASSWORD)

            tokenProvider.saveToken(
                token,
                userId.toString(),
                com.panomc.platform.util.TokenType.RESET_PASSWORD,
                expireDate,
                sqlConnection
            )

            parameters.put("link", "$uiAddress/renew-password?token=$token")

            parameters
        }
    ),
    PASSWORD_UPDATED(
        templatePath = "mail/notification/password-updated.hbs",
        subject = "Pano - Your password has been updated",
        parameterGenerator = { _: String,
                               userId: Long,
                               _: String,
                               databaseManager: DatabaseManager,
                               sqlConnection: SqlConnection,
                               _: TokenProvider ->
            val parameters = JsonObject()

            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)

            parameters.put("username", username)

            parameters
        }
    )
}