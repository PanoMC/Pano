package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetConnectedServersAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val sqlClient = getSqlClient()

        val all = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)
        val byId = all.associateBy { it.id }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val mainServerId =
            databaseManager.systemPropertyDao.getByOption("main_server", sqlClient)?.value?.toLong()
        val mainServer: Server? =
            if (mainServerId != null && mainServerId != -1L) byId[mainServerId] else null

        val selectedConfig =
            databaseManager.panelConfigDao.byUserIdAndOption(userId, "selected_server", sqlClient)
        val selectedId = selectedConfig?.value?.toLong()
        val selectedServer: Server? = if (selectedId != null) byId[selectedId] else null

        val pinned = buildList<Server> {
            if (mainServer != null) {
                add(mainServer)
            }
            if (selectedServer != null && (mainServer == null || selectedServer.id != mainServer.id)) {
                add(selectedServer)
            }
        }

        val pinnedIds = pinned.map { it.id }.toSet()
        val otherServers = all
            .filter { it.id !in pinnedIds }
            .sortedWith(
                compareBy<Server> {
                    (it.customName?.takeIf { n -> n.isNotBlank() } ?: it.name).lowercase()
                }.thenBy { it.id }
            )

        val orderedAll = pinned + otherServers

        return Successful(
            mapOf(
                "servers" to orderedAll,
                "pinned" to pinned,
                "otherServers" to otherServers
            )
        )
    }
}