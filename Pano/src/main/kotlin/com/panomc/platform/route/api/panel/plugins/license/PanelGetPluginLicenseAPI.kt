package com.panomc.platform.route.api.panel.plugins.license

import com.panomc.platform.PanoApiManager
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.LicensePanelView
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Returns the full license claims of a plugin (if it has a cached license) plus the
 * computed status fields shared with [com.panomc.platform.route.api.panel.plugins.PanelGetPluginAPI].
 *
 * The "License" card on the addon detail page hits this endpoint when the user clicks
 * "Show details" on a licensed plugin.
 */
@Endpoint
class PanelGetPluginLicenseAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val licenseManager: LicenseManager,
    private val configManager: ConfigManager,
    private val panoApiManager: PanoApiManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId/license", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)
        val pluginId = parameters.pathParameter("pluginId").string

        pluginManager.getPlugin(pluginId) ?: throw NotFound()

        val statusFields = LicensePanelView.buildLicenseFields(
            pluginId = pluginId,
            licenseManager = licenseManager,
            configManager = configManager,
            isPanoConnected = panoApiManager.isConnected()
        )

        val cached = licenseManager.getCachedLicense(pluginId)
        val claims = cached?.claims?.let {
            mapOf(
                "issuer" to it.issuer,
                "platformId" to it.platformId,
                "resourceId" to it.resourceId,
                "userId" to it.userId,
                "version" to it.version,
                "jarSha256" to it.jarSha256,
                "issuedAt" to it.issuedAtMs / 1000,
                "expiresAt" to it.expiresAtMs / 1000,
                "keyId" to it.keyId,
                "tokenId" to it.tokenId
            )
        }

        return Successful(
            mapOf(
                "data" to (statusFields + mapOf("claims" to claims))
            )
        )
    }
}
