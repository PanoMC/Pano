package com.panomc.platform.mail.mails

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

class ChangeEmailMail : Mail {
    override val templatePath = "mail/change-email.hbs"
    override val subject = "Pano - Verify E-mail Change"

    override suspend fun parameterGenerator(
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlClient: SqlClient,
        tokenProvider: TokenProvider
    ): JsonObject {
        val parameters = JsonObject()

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.CHANGE_EMAIL, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.CHANGE_EMAIL)

        tokenProvider.saveToken(token, userId.toString(), TokenType.CHANGE_EMAIL, expireDate, sqlClient)

        parameters.put("link", "$uiAddress/activate-new-email?token=$token")

        return parameters
    }
}