package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerAuthProvider
import com.panomc.platform.server.ServerRemovalService
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class ServerDisconnectAPI(
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val serverAuthProvider: ServerAuthProvider,
    private val serverRemovalService: ServerRemovalService
) : Api() {
    override val paths = listOf(Path("/server/disconnect", RouteType.POST))

    // Minecraft plugin surface — see ServerConnectAPI.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        if (!setupManager.isSetupDone()) {
            return InstallationRequired()
        }

        if (!serverAuthProvider.isAuthenticated(context)) {
            return InvalidToken()
        }

        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: return InvalidToken()

        // A managed server is created and removed from the panel: deleting its row from here would
        // leave its directory and process on the node with nothing in Pano that could reach them.
        if (server.isManaged) {
            return NoPermission()
        }

        // The same cleanup as a delete from the panel: socket closed, every dependent row and the
        // server's tokens removed, the main server handed on.
        serverRemovalService.remove(serverId, sqlClient)

        return Successful()
    }
}