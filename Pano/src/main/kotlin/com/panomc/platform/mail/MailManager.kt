package com.panomc.platform.mail

import com.github.jknack.handlebars.Handlebars
import com.google.gson.Gson
import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.i18n.I18nManager
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.SMTPException
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.io.InputStream
import java.util.*

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MailManager(
    private val configManager: ConfigManager,
    private val databaseManager: DatabaseManager,
    private val logger: Logger,
    private val gson: Gson,
    private val vertx: Vertx,
    private val i18nManager: I18nManager
) {
    // Handlebars instance for rendering mail templates
    private val handlebars by lazy { Handlebars() }
    private val mailClient: MailClient by lazy {
        val emailConfig = configManager.config.email
        val mailClientConfig = MailConfig(JsonObject.mapFrom(emailConfig))

        MailClient.createShared(vertx, mailClientConfig, "mailClient")
    }

    private val systemClassLoader = ClassLoader.getSystemClassLoader()

    private fun getWebsiteLogo(): InputStream {
        val config = configManager.config

        return try {
            if (config.filePaths["websiteLogo"] == null) {
                throw Exception()
            } else {
                val websiteLogoFile =
                    File(configManager.config.fileUploadsFolder + File.separator + config.filePaths["websiteLogo"])

                if (websiteLogoFile.exists()) {
                    websiteLogoFile.inputStream()
                } else {
                    throw Exception()
                }
            }
        } catch (_: Exception) {
            systemClassLoader.getResourceAsStream(DEFAULT_WEBSITE_LOGO_FILE)!!
        }
    }

    private fun getWebsiteLogoMimeType(): String {
        val config = configManager.config

        return if (config.filePaths["websiteLogo"] == null) {
            return "image/png" // default logo is PNG
        } else {
            val filePath = config.filePaths["websiteLogo"] as String
            val extension = filePath.substringAfterLast('.', "").lowercase()

            when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> "image/png" // fallback to PNG
            }
        }
    }

    suspend fun sendMail(sqlClient: SqlClient, userId: Long?, mail: Mail, email: String? = null) {
        val config = configManager.config
        val emailConfig = config.email

        if (!emailConfig.enabled) {
            return
        }

        val user = userId?.let { databaseManager.userDao.getById(it, sqlClient) }

        val emailAddress =
            email ?: user?.email
            ?: throw NotExists()
        val locale = user?.localeCode ?: config.locale

        val message = MailMessage()

        message.from = emailConfig.sender
        message.subject = i18nManager.translate(
            TranslationType.PLATFORM,
            locale,
            mail.subject,
            mapOf("websiteName" to config.websiteName)
        )
        message.setTo(emailAddress)

        val mailParameters = mail.generateParameters(SystemParameters(config.websiteUrl, config.websiteName), i18nManager, locale)

        // Convert MailParameters to Map<String, Any> using JsonObject
        val jsonObject = JsonObject(gson.toJson(mailParameters))
        val parameters = jsonObject.map.toMutableMap()

        val websiteLogoInputStream = getWebsiteLogo()
        val websiteLogoMimeType = getWebsiteLogoMimeType()

        parameters["websiteUrl"] = config.websiteUrl
        parameters["websiteLogo"] = websiteLogoInputStream.use { inputStream ->
            val bytes = inputStream.readBytes()
            "data:$websiteLogoMimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }
        parameters["websiteName"] = config.websiteName

        val template = mail.getTemplate(handlebars)
        message.html = template.apply(parameters)

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

    companion object {
        data class SystemParameters(
            val websiteName: String,
            val websiteUrl: String
        )
    }
}