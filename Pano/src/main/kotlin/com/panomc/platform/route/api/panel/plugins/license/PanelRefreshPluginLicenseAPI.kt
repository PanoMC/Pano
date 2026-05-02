package com.panomc.platform.route.api.panel.plugins.license

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoApiManager
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.LicensePanelView
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Forces a fresh fetch of a plugin's license from panomc.com. The panel "Refresh license"
 * button hits this; useful after the user has just completed a purchase.
 *
 * Even on failure we return 200 with the new status, so the UI can re-render. The actual
 * `licenseStatus` is what matters; the HTTP layer never throws because of license issues.
 */
@Endpoint
class PanelRefreshPluginLicenseAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val licenseManager: LicenseManager,
    private val configManager: ConfigManager,
    private val panoApiManager: PanoApiManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId/license/refresh", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)
        val pluginId = parameters.pathParameter("pluginId").string

        val wrapper = pluginManager.getPlugin(pluginId) ?: throw NotFound()
        val plugin = wrapper.plugin as? PanoPlugin
            ?: return Successful(
                mapOf(
                    "data" to LicensePanelView.buildLicenseFields(
                        pluginId,
                        licenseManager,
                        configManager,
                        panoApiManager.isConnected()
                    )
                )
            )

        val descriptor = wrapper.descriptor as PanoPluginDescriptor
        // Best-effort assumption: resourceId == pluginId. If a plugin uses a different
        // resourceId on panomc.com, it will only matter the next time the plugin itself
        // calls licenseManager.requireLicense() with the right id; this manual refresh
        // is a UX convenience, not a security boundary.
        val resourceId = pluginId
        val version = descriptor.version

        try {
            licenseManager.requireLicense(plugin, resourceId, version)
            // Re-run the plugin's own verification step so a token the host happily cached but the
            // plugin rejects (e.g. RS256 signature/issuer mismatch from a different license server)
            // is surfaced here instead of only when the operator clicks Enable. Without this the
            // panel showed "LICENSED" right after refresh while the very next start attempt threw
            // SIGNATURE_INVALID from PluginLicenseClient.
            plugin.verifyLicense()
        } catch (e: LicenseRequiredException) {
            // Plugin-side verification failures aren't recorded by requireLicense; do it here
            // so the panel reflects the real license state immediately after refresh.
            licenseManager.recordFailure(pluginId, e)
        } catch (_: Throwable) {
            // never propagate refresh failures as HTTP errors.
        }

        return Successful(
            mapOf(
                "data" to LicensePanelView.buildLicenseFields(
                    pluginId,
                    licenseManager,
                    configManager,
                    panoApiManager.isConnected()
                )
            )
        )
    }
}
