package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class UpdateStepAPI(
    private val configManager: ConfigManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/step", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("clientStep", intSchema())
                        .optionalProperty("step", intSchema())

                        .optionalProperty("websiteName", stringSchema())
                        .optionalProperty("websiteDescription", stringSchema())

                        .optionalProperty("hostname", stringSchema())
                        .optionalProperty("port", intSchema())
                        .optionalProperty("ssl", booleanSchema())
                        .optionalProperty(
                            "starttls", enumSchema(*StartTLSOptions.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty("username", stringSchema())
                        .optionalProperty("password", stringSchema())
                        .optionalProperty("sender", stringSchema())
                        .optionalProperty("authMethods", stringSchema())

                        .optionalProperty("host", stringSchema())
                        .optionalProperty("dbName", stringSchema())
                        .optionalProperty("prefix", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val step = data.getInteger("step")

        val isInputValid = validateInput(data)

        if (isInputValid) {
//            if step lower than current step, go to that step
            if (step != null && step < setupManager.getCurrentStep()) {
                setupManager.goStep(step)
            } else {
                setupManager.nextStep()
            }
        }

        return Successful(setupManager.getCurrentStepData().map)
    }

    private fun validateInput(data: JsonObject): Boolean {
        val clientStep = data.getInteger("clientStep")
        val step = data.getInteger("step")

        val websiteName = data.getString("websiteName")
        val websiteDescription = data.getString("websiteDescription")

        val hostname = data.getString("hostname")
        val port = data.getInteger("port")
        val sender = data.getString("sender")
        val username = data.getString("username")
        val password = data.getString("password")
        val ssl = data.getBoolean("ssl")
        val starttls = data.getString("starttls")
        val authMethods = data.getString("authMethods")

        val host = data.getString("host")
        val dbName = data.getString("dbName")
        val prefix = data.getString("prefix")

//        if client step does not match current step, such as 3 but supposed to 1
        if (clientStep != setupManager.getCurrentStep()) {
            return false
        }

        if (step != null) {
//        if given step is higher than current step, such as 3 -> 5
            if (step > setupManager.getCurrentStep()) {
                return false
            }

//        if given step is lower than current step, such as 3 -> 2 or 3 -> 1
            if (step < setupManager.getCurrentStep()) {
                return true
            }
        }

        if (clientStep == 0) {
            return true
        }

        if (clientStep == 1 &&
            !websiteName.isNullOrEmpty() &&
            !websiteDescription.isNullOrEmpty()
        ) {
            configManager.config.websiteName = websiteName
            configManager.config.websiteDescription = websiteDescription

            configManager.saveConfig()
            return true
        }

        if (
            clientStep == 2 &&
            !host.isNullOrEmpty() &&
            !dbName.isNullOrEmpty() &&
            !username.isNullOrEmpty()
        ) {
            val databaseOptions = configManager.config.database

            databaseOptions.host = host
            databaseOptions.name = dbName
            databaseOptions.username = username
            databaseOptions.password = if (password.isNullOrEmpty()) "" else password
            databaseOptions.prefix = if (prefix.isNullOrEmpty()) "" else prefix
            configManager.saveConfig()

            return true
        }

        if (clientStep == 3 &&
            !hostname.isNullOrEmpty() &&
            port != null &&
            !sender.isNullOrEmpty() &&
            !username.isNullOrEmpty() &&
            !password.isNullOrEmpty() &&
            ssl != null &&
            starttls != null &&
            authMethods != null
        ) {
            val mailConfiguration = configManager.config.email

            mailConfiguration.sender = sender
            mailConfiguration.hostname = hostname
            mailConfiguration.port = port
            mailConfiguration.username = username
            mailConfiguration.password = password
            mailConfiguration.ssl = ssl
            mailConfiguration.starttls = starttls
            mailConfiguration.authMethods = authMethods
            configManager.saveConfig()

            return true
        }

        if (clientStep == 3) {
            return true
        }

        return false
    }
}