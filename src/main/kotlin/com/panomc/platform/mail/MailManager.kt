package com.panomc.platform.mail

import com.panomc.platform.ErrorCode
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import com.panomc.platform.token.TokenProvider
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.web.templ.handlebars.HandlebarsTemplateEngine
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

class MailManager(
    private val configManager: ConfigManager,
    private val templateEngine: HandlebarsTemplateEngine,
    private val mailClientProvider: MailClientProvider,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) {
    private val mailClient by lazy {
        mailClientProvider.provide()
    }

    suspend fun sendMail(sqlConnection: SqlConnection, userId: Long, mail: Mail) {
        val email =
            databaseManager.userDao.getEmailFromUserId(userId, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        val emailConfig = configManager.getConfig().getJsonObject("email")
        val message = MailMessage()

        message.from = emailConfig.getString("address")
        message.subject = mail.subject
        message.setTo(email)

        message.html = templateEngine.render(
            mail.parameterGenerator(
                email,
                userId,
                configManager.getConfig().getString("ui-address"),
                databaseManager,
                sqlConnection,
                tokenProvider
            ),
            mail.templatePath
        ).await().toString()

        mailClient.sendMail(message).await()
    }
}