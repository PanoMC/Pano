package com.panomc.platform.route.api

import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.Main.Companion.VERSION
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.theme.PanelUpdateThemeSettingsAPI.Companion.THEME_SETTINGS
import com.panomc.platform.util.HashUtil.hash
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import java.io.File

@Endpoint
class GetSiteInfoAPI(
    private val configManager: ConfigManager,
    private val pluginManager: PluginManager,
    private val pluginUiManager: PluginUiManager,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager
) : Api() {
    override val paths = listOf(Path("/api/siteInfo", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()
        val config = configManager.config

        val websiteLogoPath =
            if (config.filePaths["websiteLogo"] == null) DEFAULT_WEBSITE_LOGO_FILE else configManager.config.fileUploadsFolder + File.separator + config.filePaths["websiteLogo"]
        val websiteLogoFile = File(websiteLogoPath)
        val websiteLogoHash =
            if (websiteLogoFile.exists()) websiteLogoFile.inputStream().hash() else File(DEFAULT_WEBSITE_LOGO_FILE)

        response["locale"] = config.locale
        response["websiteName"] = config.websiteName
        response["websiteDescription"] = config.websiteDescription
        response["supportEmail"] = config.supportEmail
        response["keywords"] = config.keywords
        response["panoVersion"] = VERSION
        response["websiteLogoHash"] = websiteLogoHash

        response["plugins"] = pluginUiManager.getRegisteredPlugins().toList().associate {
            it.first.pluginId to mapOf(
                "version" to pluginManager.getPlugin(it.first.pluginId).descriptor.version,
                "uiHash" to it.second
            )
        }

        response["emailEnabled"] = config.email.enabled

        val sqlClient = databaseManager.getSqlClient()
        val themeSettingsProperty = databaseManager.systemPropertyDao.getByOption(THEME_SETTINGS, sqlClient)

        var themeSettings = JsonObject()

        if (themeSettingsProperty != null) {
            val currentThemeSettings = JsonObject(themeSettingsProperty.value).getJsonObject(uiManager.activeTheme)

            if (currentThemeSettings != null) {
                themeSettings = currentThemeSettings
            }
        }

        response["themeSettings"] = themeSettings

        return Successful(response)
    }
}