package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
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
    private val authProvider: AuthProvider,
    private val panoApiManager: PanoApiManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                param("type", arraySchema().items(enumSchema(*SettingType.entries.map { it.type }.toTypedArray())))
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PLATFORM_SETTINGS, context)

        val parameters = getParameters(context)

        val settingType =
            SettingType.valueOf(type = parameters.queryParameter("type")?.jsonArray?.first() as String?)

        val result = mutableMapOf<String, Any?>()

        if (settingType == SettingType.GENERAL) {
            val panoAccountConfig = configManager.config.panoAccount

            if (panoApiManager.isConnected()) {
                val panoAccount = JsonObject()
                panoAccount.put("platformId", panoAccountConfig.platformId)
                panoAccount.put("username", panoAccountConfig.username)
                panoAccount.put("email", panoAccountConfig.email)

                result["panoAccount"] = panoAccount
            }

            result["updatePeriod"] = configManager.config.updatePeriod.name
            result["locale"] = configManager.config.locale

            val emailConfig = configManager.config.email

            val email = JsonObject.mapFrom(emailConfig)

            email.remove("password")

            result["email"] = email
        }

        if (settingType == SettingType.WEBSITE) {
            result["websiteName"] = configManager.config.websiteName
            result["websiteDescription"] = configManager.config.websiteDescription
            result["supportEmail"] = configManager.config.supportEmail
            result["serverIpAddress"] = configManager.config.serverIpAddress
            result["serverGameVersion"] = configManager.config.serverGameVersion
            result["keywords"] = configManager.config.keywords
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