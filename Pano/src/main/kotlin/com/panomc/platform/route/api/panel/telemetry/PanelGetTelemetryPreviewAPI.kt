package com.panomc.platform.route.api.panel.telemetry

import com.panomc.platform.TelemetryManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Shows the owner exactly what the daily usage-data heartbeat contains, built the same way and
 * from the same code as the payload that is actually posted.
 */
@Endpoint
class PanelGetTelemetryPreviewAPI(
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager,
    private val telemetryManager: TelemetryManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/telemetry/preview", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        return Successful(
            mapOf(
                "enabled" to (configManager.config.telemetry?.enabled ?: true),
                "installId" to telemetryManager.getInstallId(),
                "payload" to telemetryManager.buildPayload()
            )
        )
    }
}
