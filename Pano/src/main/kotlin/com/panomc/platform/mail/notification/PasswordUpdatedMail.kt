package com.panomc.platform.mail.notification

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.NotificationMail
import com.panomc.platform.token.TokenProvider
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

class PasswordUpdatedMail : NotificationMail() {
    override val templatePath = "${super.templatePath}/password-updated.hbs"
    override val subject = "Pano - Your password has been updated"

    override suspend fun parameterGenerator(
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlClient: SqlClient,
        tokenProvider: TokenProvider
    ): JsonObject {
        val parameters = JsonObject()

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)

        parameters.put("username", username)

        return parameters
    }
}