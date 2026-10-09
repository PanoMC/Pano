package com.panomc.platform.route.api

import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.Main
import com.panomc.platform.Main.Companion.VERSION
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.theme.PanelUpdateThemeHomeAPI.Companion.HOME_PAGE
import com.panomc.platform.route.api.panel.theme.PanelUpdateThemeSettingsAPI.Companion.THEME_SETTINGS
import com.panomc.platform.ui.ThemeCompatibility
import com.panomc.platform.util.HashUtil.hash
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import org.pf4j.PluginDescriptor
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetSiteInfoAPI(
    private val configManager: ConfigManager,
    private val pluginManager: PluginManager,
    private val pluginUiManager: PluginUiManager,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/site-info", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "What a front-end needs to boot: language, site texts, mode, plugins and theme settings.",
        tag = "site",
        response = objectSchema()
            .optionalProperty("userLocaleCode", stringSchema().nullable())
            .optionalProperty("locales", arraySchema().items(CoreSchemas.locale))
            .requiredProperty("locale", stringSchema())
            .requiredProperty("platformLocale", stringSchema())
            .requiredProperty("allowUserLocaleSelection", booleanSchema())
            .requiredProperty("developmentMode", booleanSchema())
            .requiredProperty("usageMode", enumSchema("WEBSITE", "SERVERS", "BOTH"))
            .requiredProperty("websiteName", stringSchema())
            .requiredProperty("websiteDescription", stringSchema())
            .requiredProperty("ipAddress", stringSchema())
            .requiredProperty("websiteUrl", stringSchema())
            .requiredProperty("hasRegisterAgreement", booleanSchema())
            .requiredProperty("supportEmail", stringSchema())
            .requiredProperty("keywords", arraySchema().items(stringSchema()))
            .requiredProperty("panoVersion", stringSchema())
            .requiredProperty("websiteLogoHash", stringSchema())
            .requiredProperty("faviconHash", stringSchema())
            .requiredProperty(
                "plugins",
                objectSchema().additionalProperties(
                    objectSchema()
                        .requiredProperty("version", stringSchema().nullable())
                        .requiredProperty("uiHash", stringSchema())
                        .requiredProperty("dependencies", arraySchema().items(stringSchema()))
                )
            )
            .requiredProperty("emailEnabled", booleanSchema())
            .requiredProperty("isDemo", booleanSchema())
            .requiredProperty("themeSettings", objectSchema())
            .requiredProperty("homePage", stringSchema().nullable())
    )

    // panel-ui blocks on this during SSR, with no user cookie — the panel will not boot without it.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    companion object {
        // dependencies (optional ones included) let the UI loader finish a dependency's onLoad before its dependents'.
        internal fun pluginInfo(descriptor: PluginDescriptor?, uiHash: String) = mapOf(
            "version" to descriptor?.version,
            "uiHash" to uiHash,
            "dependencies" to (descriptor?.dependencies?.map { d -> d.pluginId } ?: emptyList<String>())
        )
    }

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
        response["developmentMode"] = config.developmentMode
        response["usageMode"] = config.effectiveUsageMode.name
        response["websiteName"] = config.websiteName
        response["websiteDescription"] = config.websiteDescription
        response["ipAddress"] = config.serverIpAddress
        response["websiteUrl"] = config.websiteUrl
        response["hasRegisterAgreement"] = config.registerAgreement.isNotBlank()
        response["supportEmail"] = config.supportEmail
        response["keywords"] = config.keywords
        response["panoVersion"] = VERSION
        response["websiteLogoHash"] = websiteLogoHash
        response["faviconHash"] = faviconHash

        response["plugins"] = pluginUiManager.getActiveRegisteredPlugins(pluginManager).associate {
            it.first.pluginId to pluginInfo(pluginManager.getPlugin(it.first.pluginId)?.descriptor, it.second)
        }

        response["emailEnabled"] = config.email.enabled
        response["isDemo"] = Main.IS_DEMO

        val themeSettingsProperty = databaseManager.systemPropertyDao.getByOption(THEME_SETTINGS, sqlClient)

        var themeSettings = JsonObject()

        if (themeSettingsProperty != null) {
            val currentThemeSettings = JsonObject(themeSettingsProperty.value).getJsonObject(uiManager.activeTheme)

            if (currentThemeSettings != null) {
                themeSettings = currentThemeSettings
            }
        }

        response["themeSettings"] = themeSettings

        // The admin's home page pick for the active theme ("store", "custom:/rules") or null = the theme's
        // own default. Its own property: saving the theme settings replaces that whole object (doc 01 section 9).
        response["homePage"] = ThemeCompatibility.homeValueFor(
            databaseManager.systemPropertyDao.getByOption(HOME_PAGE, sqlClient)?.value,
            uiManager.activeTheme
        )

        return Successful(response)
    }
}