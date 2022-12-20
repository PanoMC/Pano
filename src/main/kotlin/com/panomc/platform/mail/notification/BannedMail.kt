package com.panomc.platform.mail.notification

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.NotificationMail
import com.panomc.platform.token.TokenProvider
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection

class BannedMail : NotificationMail() {
    override val templatePath = "${super.templatePath}/banned.hbs"
    override val subject = "Pano - Your have been banned!"

    override suspend fun parameterGenerator(
        email: String,
        userId: Long,
        uiAddress: String,
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        tokenProvider: TokenProvider
    ): JsonObject {
        val parameters = JsonObject()

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)

        parameters.put("username", username)

        return parameters
    }
}