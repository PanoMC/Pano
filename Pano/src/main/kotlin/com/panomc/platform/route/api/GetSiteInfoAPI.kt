package com.panomc.platform.route.api

import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.Main.Companion.VERSION
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import com.panomc.platform.util.HashUtil.hash
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import java.io.File

@Endpoint
class GetSiteInfoAPI(
    private val configManager: ConfigManager,
    private val pluginManager: PluginManager,
    private val pluginUiManager: PluginUiManager
) : Api() {
    override val paths = listOf(Path("/api/siteInfo", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any>()
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

        return Successful(response)
    }
}