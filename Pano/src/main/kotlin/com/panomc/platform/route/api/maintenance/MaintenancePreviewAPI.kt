package com.panomc.platform.route.api.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * The maintenance page as a visitor would get it, for an admin who wants to look at it on the site itself before (or
 * while) maintenance mode is on: the saved page with the saved settings, rendered for an anonymous visitor. Nothing is
 * switched on and the caller's own skip cookie is left alone. The panel's "preview" link opens it in a new tab. A panel
 * route (sign-in and panel access first), so it also answers while maintenance mode is on.
 */
@Endpoint
class MaintenancePreviewAPI(
    private val authProvider: AuthProvider,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path(MaintenanceModeManager.PREVIEW_PATH, RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val html = maintenanceModeManager.renderMaintenancePage(context, MaintenanceModeManager.Access.ANONYMOUS, null)
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return null
        }

        // 200, not the 503 visitors get: this is a working preview, not an outage.
        response
            .setStatusCode(200)
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .putHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            .putHeader("Pragma", "no-cache")
            .putHeader("X-Robots-Tag", "noindex")
            .end(html)

        return null
    }
}
