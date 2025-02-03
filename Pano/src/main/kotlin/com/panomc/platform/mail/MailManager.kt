package com.panomc.platform.mail

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.token.TokenProvider
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.SMTPException
import io.vertx.ext.web.templ.handlebars.HandlebarsTemplateEngine
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MailManager(
    private val configManager: ConfigManager,
    private val templateEngine: HandlebarsTemplateEngine,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val logger: Logger,
    private val vertx: Vertx
) {
    private val mailClient: MailClient by lazy {
        val emailConfig = configManager.config.email
        val mailClientConfig = MailConfig(JsonObject.mapFrom(emailConfig))

        MailClient.createShared(vertx, mailClientConfig, "mailClient")
    }

    suspend fun sendMail(sqlClient: SqlClient, userId: Long, mail: Mail, email: String? = null) {
        val emailAddress =
            email ?: databaseManager.userDao.getEmailFromUserId(userId, sqlClient)
            ?: throw NotExists()

        val emailConfig = configManager.config.email
        val message = MailMessage()

        message.from = emailConfig.sender
        message.subject = mail.subject
        message.setTo(emailAddress)

        message.html = templateEngine.render(
            mail.parameterGenerator(
                emailAddress,
                userId,
                configManager.config.uiAddress,
                databaseManager,
                sqlClient,
                tokenProvider
            ),
            mail.templatePath
        ).coAwait().toString()

        mailClient.sendMail(message).coAwait()
    }

    suspend fun validateConfig(config: JsonObject, sender: String) {
        try {
            val mailConfig = MailConfig(config)

            val mailClient = MailClient.create(vertx, mailConfig)

            val message = MailMessage()

            message.from = sender
            message.subject = "Pano Platform E-mail test"
            message.setTo("no-reply@duruer.dev")
            message.html = "Hello world!"

            mailClient.sendMail(message).coAwait()

            mailClient.close()
        } catch (e: Exception) {
            logger.error(e.toString())

            throw InvalidData(extras = mapOf("mailError" to e.message))
        } catch (e: SMTPException) {
            logger.error(e.toString())

            throw InvalidData(extras = mapOf("mailError" to e.message))
        }
    }
}