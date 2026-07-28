package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Every editable block of the maintenance page, keyed by the enum name the panel's tabs use.
 * Missing files are written from the bundled defaults on the way out, so the editor always opens
 * on real markup.
 */
@Endpoint
class PanelGetMaintenancePageAPI(
    private val authProvider: AuthProvider,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/page", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val templates = MaintenanceModeManager.PageTemplate.entries.associate {
            it.name to maintenanceModeManager.templateSource(it)
        }

        return Successful(
            mapOf(
                "templates" to templates,
                "customPage" to maintenanceModeManager.settings().customPage
            )
        )
    }
}
