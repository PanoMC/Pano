package com.panomc.platform.util

import com.panomc.platform.ErrorCode
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.web.templ.handlebars.HandlebarsTemplateEngine
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

class MailUtil(
    private val configManager: ConfigManager,
    private val templateEngine: HandlebarsTemplateEngine,
    private val mailClientProvider: MailClientProvider,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) {
    private val mailClient by lazy {
        mailClientProvider.provide()
    }

    suspend fun sendMail(sqlConnection: SqlConnection, userId: Long, mailType: MailType) {
        val email =
            databaseManager.userDao.getEmailFromUserId(userId, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        val emailConfig = configManager.getConfig().getJsonObject("email")
        val message = MailMessage()

        message.from = emailConfig.getString("address")
        message.subject = mailType.subject
        message.setTo(email)

        message.html = templateEngine.render(
            mailType.parameterGenerator.invoke(
                email,
                userId,
                configManager.getConfig().getString("ui-address"),
                databaseManager,
                tokenProvider
            ),
            mailType.templatePath
        ).await().toString()

        mailClient.sendMail(message).await()
    }
}