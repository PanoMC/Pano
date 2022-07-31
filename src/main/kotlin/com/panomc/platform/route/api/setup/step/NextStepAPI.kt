package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.SetupApi
import com.panomc.platform.model.Successful
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class NextStepAPI(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/step/nextStep")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("step", intSchema())
                        .optionalProperty("websiteName", stringSchema())
                        .optionalProperty("websiteDescription", stringSchema())
                        .optionalProperty("host", stringSchema())
                        .optionalProperty("port", intSchema())
                        .optionalProperty("address", stringSchema())
                        .optionalProperty("dbName", stringSchema())
                        .optionalProperty("username", stringSchema())
                        .optionalProperty("password", stringSchema())
                        .optionalProperty("prefix", stringSchema())
                        .optionalProperty("useSSL", booleanSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val clientStep = data.getInteger("step")
        val websiteName = data.getString("websiteName")
        val websiteDescription = data.getString("websiteDescription")
        val host = data.getString("host")
        val port = data.getInteger("port")
        val address = data.getString("address")
        val dbName = data.getString("dbName")
        val username = data.getString("username")
        val password = data.getString("password")
        val prefix = data.getString("prefix")
        val useSSL = data.getBoolean("useSSL")

        if (clientStep == setupManager.getStep()) {
            var passStep = false

            if (clientStep == 0)
                passStep = true
            else if (clientStep == 1 &&
                !websiteName.isNullOrEmpty() &&
                !websiteDescription.isNullOrEmpty()
            ) {
                configManager.getConfig().put("website-name", websiteName)
                configManager.getConfig().put("website-description", websiteDescription)

                passStep = true
            } else if (
                clientStep == 2 &&
                !host.isNullOrEmpty() &&
                !dbName.isNullOrEmpty() &&
                !username.isNullOrEmpty()
            ) {
                val databaseOptions = configManager.getConfig().getJsonObject("database")

                databaseOptions.put("host", host)
                databaseOptions.put("name", dbName)
                databaseOptions.put("username", username)
                databaseOptions.put("password", if (password.isNullOrEmpty()) "" else password)
                databaseOptions.put("prefix", if (prefix.isNullOrEmpty()) "" else prefix)

                passStep = true
            } else if (clientStep == 3 &&
                !host.isNullOrEmpty() &&
                port != null &&
                !address.isNullOrEmpty() &&
                !username.isNullOrEmpty() &&
                !password.isNullOrEmpty() &&
                useSSL != null
            ) {
                val mailConfiguration = configManager.getConfig().getJsonObject("email")

                mailConfiguration.put("address", address)
                mailConfiguration.put("host", host)
                mailConfiguration.put("port", port)
                mailConfiguration.put("username", username)
                mailConfiguration.put("password", password)
                mailConfiguration.put("SSL", useSSL)

                passStep = true
            }

            if (passStep)
                setupManager.nextStep()
        }

        return Successful(setupManager.getCurrentStepData().map)
    }
}