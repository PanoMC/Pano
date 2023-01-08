package com.panomc.platform.mail.mails

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection

class ChangeEmailMail : Mail {
    override val templatePath = "mail/change-email.hbs"
    override val subject = "Pano - Verify E-mail Change"

    override suspend fun parameterGenerator(
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        tokenProvider: TokenProvider
    ): JsonObject {
        val parameters = JsonObject()

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.CHANGE_EMAIL, sqlConnection)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.CHANGE_EMAIL)

        tokenProvider.saveToken(token, userId.toString(), TokenType.CHANGE_EMAIL, expireDate, sqlConnection)

        parameters.put("link", "$uiAddress/activate-new-email?token=$token")

        return parameters
    }
}