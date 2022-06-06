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
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

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
                Bodies.json(
                    Schemas.objectSchema()
                        .property("step", Schemas.intSchema())
                        .optionalProperty("websiteName", Schemas.stringSchema())
                        .optionalProperty("websiteDescription", Schemas.stringSchema())
                        .optionalProperty("host", Schemas.stringSchema())
                        .optionalProperty("dbName", Schemas.stringSchema())
                        .optionalProperty("username", Schemas.stringSchema())
                        .optionalProperty("password", Schemas.stringSchema())
                        .optionalProperty("prefix", Schemas.stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val clientStep = data.getInteger("step")

        if (clientStep == setupManager.getStep()) {
            var passStep = false

            if (clientStep == 0)
                passStep = true
            else if (clientStep == 1 && !data.getString("websiteName")
                    .isNullOrEmpty() && !data.getString("websiteDescription").isNullOrEmpty()
            ) {
                configManager.getConfig().put("website-name", data.getString("websiteName"))
                configManager.getConfig().put("website-description", data.getString("websiteDescription"))

                passStep = true
            } else if (
                clientStep == 2 &&
                !data.getString("host").isNullOrEmpty() &&
                !data.getString("dbName").isNullOrEmpty() &&
                !data.getString("username").isNullOrEmpty()
            ) {
                val databaseOptions = configManager.getConfig().getJsonObject("database")

                databaseOptions.put("host", data.getString("host"))
                databaseOptions.put("name", data.getString("dbName"))
                databaseOptions.put(
                    "username",
                    data.getString("username")
                )
                databaseOptions.put(
                    "password",
                    if (data.getString("password").isNullOrEmpty()) "" else data.getString("password")
                )
                databaseOptions.put(
                    "prefix",
                    if (data.getString("prefix").isNullOrEmpty()) "" else data.getString("prefix")
                )

                passStep = true
            }

            if (passStep)
                setupManager.nextStep()
        }

        return Successful(setupManager.getCurrentStepData().map)
    }
}