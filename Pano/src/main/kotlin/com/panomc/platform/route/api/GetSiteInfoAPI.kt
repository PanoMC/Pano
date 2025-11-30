package com.panomc.platform.route.api

import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.Main.Companion.VERSION
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.theme.PanelUpdateThemeSettingsAPI.Companion.THEME_SETTINGS
import com.panomc.platform.util.HashUtil.hash
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class GetSiteInfoAPI(
    private val configManager: ConfigManager,
    private val pluginManager: PluginManager,
    private val pluginUiManager: PluginUiManager,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/siteInfo", RouteType.GET))

    private val systemClassLoader = ClassLoader.getSystemClassLoader()

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()
        val config = configManager.config

        val websiteLogoHash = if (config.filePaths.websiteLogoFile == null) {
            systemClassLoader.getResourceAsStream(DEFAULT_WEBSITE_LOGO_FILE)!!.hash()
        } else {
            config.filePaths.websiteLogoFile!!.hash
        }

        val faviconHash = if (config.filePaths.faviconFile == null) {
            systemClassLoader.getResourceAsStream(DEFAULT_WEBSITE_LOGO_FILE)!!.hash()
        } else {
            config.filePaths.faviconFile!!.hash
        }

        var locale = config.locale
        val sqlClient = databaseManager.getSqlClient()

        val isLoggedIn = authProvider.isLoggedIn(context)

        if (isLoggedIn) {
            val userId = authProvider.getUserIdFromRoutingContext(context)

            val userLocaleCode = databaseManager.userDao.getLocaleCodeById(userId, sqlClient)

            if (userLocaleCode != null) {
                locale = userLocaleCode
            }

            response["userLocaleCode"] = userLocaleCode
        }

        if (config.allowUserLocaleSelection) {
            response["locales"] = databaseManager.localeDao.getAll(sqlClient)
        }

        response["locale"] = locale
        response["platformLocale"] = config.locale
        response["allowUserLocaleSelection"] = config.allowUserLocaleSelection
        response["websiteName"] = config.websiteName
        response["websiteDescription"] = config.websiteDescription
        response["ipAddress"] = config.serverIpAddress
        response["websiteUrl"] = config.websiteUrl
        response["registerAgreement"] = config.registerAgreement
        response["supportEmail"] = config.supportEmail
        response["keywords"] = config.keywords
        response["panoVersion"] = VERSION
        response["websiteLogoHash"] = websiteLogoHash
        response["faviconHash"] = faviconHash

        response["plugins"] = pluginUiManager.getRegisteredPlugins().toList().associate {
            it.first.pluginId to mapOf(
                "version" to pluginManager.getPlugin(it.first.pluginId).descriptor.version,
                "uiHash" to it.second
            )
        }

        response["emailEnabled"] = config.email.enabled

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