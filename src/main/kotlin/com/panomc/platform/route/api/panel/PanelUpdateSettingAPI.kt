package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import com.panomc.platform.util.UpdatePeriod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class PanelUpdateSettingAPI(
    setupManager: SetupManager,
    authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.PUT

    override val routes = arrayListOf("/api/panel/settings")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                Bodies.json(
                    objectSchema()
                        .optionalProperty(
                            "updatePeriod",
                            enumSchema(*UpdatePeriod.values().map { it.period }.toTypedArray())
                        )
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val updatePeriod = UpdatePeriod.valueOf(period = data.getString("updatePeriod"))

        if (updatePeriod != null) {
            configManager.getConfig().put("update-period", updatePeriod.period)

            configManager.saveConfig()
        }

        return Successful()
    }
}