package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema

@Endpoint
class PanelGetSettingsAPI(
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                param("type", arraySchema().items(enumSchema(*SettingType.values().map { it.type }.toTypedArray())))
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PLATFORM_SETTINGS, context)

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
            result["supportEmail"] = configManager.getConfig().getString("support-email")
            result["serverIpAddress"] = configManager.getConfig().getString("server-ip-address")
            result["serverGameVersion"] = configManager.getConfig().getString("server-game-version")
            result["keywords"] = configManager.getConfig().getJsonArray("keywords")
        }

        return Successful(result)
    }

    enum class SettingType(val type: String, val value: Int) {
        GENERAL("general", 0),
        WEBSITE("website", 1);

        override fun toString(): String {
            return type
        }

        companion object {
            fun valueOf(type: String?) = values().find { it.type == type }
            fun valueOf(value: Int) = values().find { it.value == value }
        }
    }
}