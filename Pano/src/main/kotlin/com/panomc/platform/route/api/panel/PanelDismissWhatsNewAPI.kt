package com.panomc.platform.route.api.panel

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelConfig
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelDismissWhatsNewAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/dismissWhatsNew", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = getSqlClient()

        val option = "dismissed_whats_new_version"
        val version = Main.VERSION

        val panelConfig = databaseManager.panelConfigDao.byUserIdAndOption(userId, option, sqlClient)

        if (panelConfig != null) {
            databaseManager.panelConfigDao.updateValueById(panelConfig.id, version, sqlClient)
        } else {
            databaseManager.panelConfigDao.add(
                PanelConfig(userId = userId, option = option, value = version),
                sqlClient
            )
        }

        return Successful()
    }
}
