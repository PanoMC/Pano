package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SettingType
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema

@Endpoint
class PanelGetSettingsAPI(
    setupManager: SetupManager,
    authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/settings")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                param("type", arraySchema().items(enumSchema(*SettingType.values().map { it.type }.toTypedArray())))
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val settingType =
            SettingType.valueOf(type = parameters.queryParameter("type")?.jsonArray?.first() as String?)

        val result = mutableMapOf<String, Any?>()

        if (settingType == SettingType.GENERAL) {
            result["updatePeriod"] = configManager.getConfig().getString("update-period")
            result["locale"] = configManager.getConfig().getString("locale")
        }

        if (settingType == SettingType.WEBSITE) {
            result["websiteName"] = configManager.getConfig().getString("website-name")
            result["websiteDescription"] = configManager.getConfig().getString("website-description")
            result["serverIpAddress"] = configManager.getConfig().getString("server-ip-address")
            result["keywords"] = configManager.getConfig().getJsonArray("keywords")
        }

        return Successful(result)
    }
}