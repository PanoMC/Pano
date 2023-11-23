package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.InvalidData
import com.panomc.platform.model.*
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.await
import org.slf4j.Logger

@Endpoint
class VerifyMailConfigurationAPI(private val logger: Logger, setupManager: SetupManager) : SetupApi(setupManager) {
    override val paths = listOf(Path("/api/setup/verifyMailConfiguration", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("useSSL", booleanSchema())
                        .property("useTLS", booleanSchema())
                        .property("address", stringSchema())
                        .property("host", stringSchema())
                        .property("username", stringSchema())
                        .property("password", stringSchema())
                        .property("port", intSchema())
                        .optionalProperty("authMethod", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val address = data.getString("address")
        val host = data.getString("host")
        val port = data.getInteger("port")
        val useSSL = data.getBoolean("useSSL")
        val useTLS = data.getBoolean("useTLS")
        val authMethod = data.getString("authMethod")
        val username = data.getString("username")
        val password = data.getString("password")

        val mailConfig = MailConfig()

        mailConfig.hostname = host
        mailConfig.port = port

        if (useSSL) {
            mailConfig.isSsl = true
        }

        if (useTLS) {
            mailConfig.starttls = StartTLSOptions.REQUIRED
        }

        mailConfig.username = username
        mailConfig.password = password

        if (authMethod != null) {
            mailConfig.authMethods = authMethod
        }

        val mailClient: MailClient

        try {
            mailClient = MailClient.create(context.vertx(), mailConfig)
        } catch (e: Exception) {
            logger.error(e.toString())

            throw InvalidData(extras = mapOf("mailError" to e.message))
        }

        val message = MailMessage()

        message.from = address
        message.subject = "Pano Platform E-mail test"
        message.setTo("no-reply@duruer.dev")
        message.html = "Hello world!"

        try {
            mailClient.sendMail(message).await()
        } catch (e: Exception) {
            logger.error(e.toString())

            throw InvalidData(extras = mapOf("mailError" to e.message))
        }

        mailClient.close()

        return Successful()
    }
}