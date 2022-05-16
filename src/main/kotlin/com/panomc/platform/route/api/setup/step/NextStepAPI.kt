package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.SetupApi
import com.panomc.platform.model.Successful
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class NextStepAPI(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/step/nextStep")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

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