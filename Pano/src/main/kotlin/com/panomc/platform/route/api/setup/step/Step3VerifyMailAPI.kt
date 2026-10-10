package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.InvalidData
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.PanoMailService
import com.panomc.platform.mail.PanoMailVerify
import com.panomc.platform.model.*
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class Step3VerifyMailAPI(
    private val mailManager: MailManager,
    private val panoMailService: PanoMailService
) : SetupApi() {
    override val paths = listOf(Path("/setup/steps/3/verify", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .optionalProperty("hostname", stringSchema())
                        .optionalProperty("port", intSchema())
                        .optionalProperty("ssl", booleanSchema())
                        .optionalProperty(
                            "starttls",
                            enumSchema(*StartTLSOptions.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty("username", stringSchema())
                        .optionalProperty("password", stringSchema())
                        .optionalProperty("sender", stringSchema())
                        .optionalProperty("authMethods", stringSchema())
                        .optionalProperty("provider", enumSchema(MailManager.PROVIDER_SMTP, MailManager.PROVIDER_PANO_MAIL))
                        .optionalProperty("name", stringSchema())
                        .optionalProperty("localPart", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val config = parameters.body().jsonObject

        if (config.getString("provider") == MailManager.PROVIDER_PANO_MAIL) {
            return Successful(PanoMailVerify.run(panoMailService, config.getString("name"), config.getString("localPart")))
        }

        PanoMailVerify.requireSmtpFields(config)

        mailManager.validateConfig(config, config.getString("sender"))

        return Successful()
    }
}