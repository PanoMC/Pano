package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.license.deriveThemeLicenseStatusLabel
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Forces a fresh fetch of a theme's license from panomc.com. The panel "Refresh license"
 * button on the theme detail page hits this; useful after the operator has just completed
 * a purchase, reconnected the panomc.com account, etc.
 *
 * Mirrors [com.panomc.platform.route.api.panel.plugins.license.PanelRefreshPluginLicenseAPI].
 * Never throws on license errors; returns 200 with the new status so the UI can re-render.
 */
@Endpoint
class PanelRefreshThemeLicenseAPI(
    private val authProvider: AuthProvider,
    private val uiManager: UIManager,
    private val licenseManager: LicenseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId/license/refresh", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val parameters = getParameters(context)
        val themeId = parameters.pathParameter("themeId").string

        val theme = uiManager.installedThemeList.find { it.id == themeId }
            ?: throw NotFound()

        // Free themes have nothing to refresh; just echo the (NOT_PREMIUM) label.
        if (!theme.premium) {
            return Successful(
                mapOf("data" to mapOf("licenseStatus" to deriveThemeLicenseStatusLabel(theme, licenseManager)))
            )
        }

        // Clear any cached failure so the next requireThemeLicense pass is fresh, then
        // re-fetch. License-side errors are recorded on themeFailures by the manager; we
        // only swallow them at the HTTP layer.
        licenseManager.clearThemeFailure(themeId)
        try {
            val normalizedVersion = theme.version.removePrefix("v")
            licenseManager.requireThemeLicense(themeId, normalizedVersion, theme.hash.lowercase())
        } catch (_: LicenseRequiredException) {
            // Already recorded; panel reads it back from the status label below.
        } catch (_: Throwable) {
            // Network blips etc. — never propagate as HTTP 5xx, the panel handles all of
            // these as "UNKNOWN" / NETWORK_ERROR labels.
        }

        return Successful(
            mapOf(
                "data" to mapOf(
                    "licenseStatus" to deriveThemeLicenseStatusLabel(theme, licenseManager)
                )
            )
        )
    }
}
