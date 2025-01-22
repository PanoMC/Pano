package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.mail.MailManager
import com.panomc.platform.model.*
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class Step3VerifyMailAPI(
    private val mailManager: MailManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/steps/3/verify", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("hostname", stringSchema())
                        .requiredProperty("port", intSchema())
                        .optionalProperty("ssl", booleanSchema())
                        .requiredProperty(
                            "starttls",
                            enumSchema(*StartTLSOptions.entries.map { it.name }.toTypedArray())
                        )
                        .requiredProperty("username", stringSchema())
                        .requiredProperty("password", stringSchema())
                        .requiredProperty("sender", stringSchema())
                        .optionalProperty("authMethods", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val config = parameters.body().jsonObject

        val sender = config.getString("sender")

        mailManager.validateConfig(config, sender)

        return Successful()
    }
}