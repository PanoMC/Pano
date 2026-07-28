package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelClearMaintenanceBannedIpsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/banned-ips/clear", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val sqlClient = getSqlClient()
        val authUserId = authProvider.getUserIdFromRoutingContext(context)
        val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

        // The manager writes the MaintenanceModeUnbannedIpLog itself, but only when it knows who
        // is acting.
        val removed = maintenanceModeManager.removeAllBans(authUserId, authUsername)

        return Successful(mapOf("removed" to removed))
    }
}
