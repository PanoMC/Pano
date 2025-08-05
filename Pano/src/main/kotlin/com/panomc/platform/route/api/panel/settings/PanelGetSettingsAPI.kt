package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.Main
import com.panomc.platform.PanoApiManager
import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema

@Endpoint
class PanelGetSettingsAPI(
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    private val panoApiManager: PanoApiManager,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                param("type", arraySchema().items(enumSchema(*SettingType.entries.map { it.name }.toTypedArray())))
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val settingTypeQuery = parameters.queryParameter("type")?.jsonArray?.first() as String?
        val settingType = if (settingTypeQuery == null) null else SettingType.valueOf(settingTypeQuery)

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

        if (settingType == SettingType.UPDATES) {
            val sqlClient = databaseManager.getSqlClient()

            result["platformUpdate"] =
                databaseManager.systemPropertyDao.getByOption(UpdateManager.PLATFORM_UPDATE_CHECK_INFO, sqlClient)
        }

        if (settingType == SettingType.ABOUT) {
            result["platformVersion"] = Main.VERSION
            result["platformStage"] = Main.STAGE.toString()
        }

        return Successful(result)
    }

    enum class SettingType {
        GENERAL,
        WEBSITE,
        UPDATES,
        ABOUT;
    }
}