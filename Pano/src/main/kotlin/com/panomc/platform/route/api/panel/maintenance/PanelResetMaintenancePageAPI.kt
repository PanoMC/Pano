package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Hands the page back to Pano: `custom-page` off, `maintenance/page.hbs` recomposed from the
 * title/message/CSS fields, and future default designs apply again. The way out of the full-page
 * editor, and the reason it is safe to enter.
 */
@Endpoint
class PanelResetMaintenancePageAPI(
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/page/reset", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val currentMaintenance: PanoConfig.Companion.MaintenanceConfig? = configManager.config.maintenance
        val maintenanceConfig = currentMaintenance ?: PanoConfig.Companion.MaintenanceConfig().also {
            configManager.config.maintenance = it
        }

        maintenanceConfig.customPage = false

        configManager.saveConfig()

        val templates = maintenanceModeManager.resetTemplates().mapKeys { it.key.name }

        return Successful(mapOf("templates" to templates))
    }
}
