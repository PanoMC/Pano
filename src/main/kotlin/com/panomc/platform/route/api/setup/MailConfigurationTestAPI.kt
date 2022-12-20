package com.panomc.platform.route.api.setup

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.await
import org.slf4j.Logger

@Endpoint
class MailConfigurationTestAPI(private val logger: Logger, setupManager: SetupManager) : SetupApi(setupManager) {
    override val paths = listOf(Path("/api/setup/mailConfigurationTest", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("useSSL", booleanSchema())
                        .property("address", stringSchema())
                        .property("host", stringSchema())
                        .property("username", stringSchema())
                        .property("password", stringSchema())
                        .property("port", intSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val address = data.getString("address")
        val host = data.getString("host")
        val port = data.getInteger("port")
        val useSSL = data.getBoolean("useSSL")
        val username = data.getString("username")
        val password = data.getString("password")

        val mailConfig = MailConfig()

        mailConfig.hostname = host
        mailConfig.port = port

        if (useSSL) {
            mailConfig.starttls = StartTLSOptions.REQUIRED
            mailConfig.isSsl = true
        }

        mailConfig.username = username
        mailConfig.password = password

        mailConfig.authMethods = "PLAIN"

        val mailClient = MailClient.create(context.vertx(), mailConfig)

        val message = MailMessage()

        message.from = address
        message.subject = "Pano Platform E-mail test"
        message.setTo("no-reply@duruer.dev")
        message.html = "Hello world!"

        try {
            mailClient.sendMail(message).await()
        } catch (e: Exception) {
            logger.error(e.toString())

            throw Error(ErrorCode.INVALID_DATA)
        }

        mailClient.close()

        return Successful()
    }
}