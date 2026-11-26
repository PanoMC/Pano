package com.panomc.platform.route.api.panel.dashboard

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelConfig
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Hides the maintenance banner at the top of the panel for the signed-in user. The dismissals are cleared when
 * maintenance mode is turned on again, so the banner comes back then.
 */
@Endpoint
class PanelCloseMaintenanceAlertAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/dashboard/closeMaintenanceAlert", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = getSqlClient()

        val panelConfig = databaseManager.panelConfigDao.byUserIdAndOption(userId, OPTION, sqlClient)

        if (panelConfig != null) {
            databaseManager.panelConfigDao.updateValueById(panelConfig.id, "true", sqlClient)
        } else {
            databaseManager.panelConfigDao.add(PanelConfig(userId = userId, option = OPTION, value = "true"), sqlClient)
        }

        return Successful()
    }

    companion object {
        const val OPTION = "dismissed_maintenance_alert"
    }
}
