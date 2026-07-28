package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NeedPermission
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerAuthProvider
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.setup.SetupManager
import io.vertx.core.http.ServerWebSocket
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Endpoint
class ServerConnectAPI(
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val serverAuthProvider: ServerAuthProvider,
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/server/connection", RouteType.GET))

    // Authenticates with a server token, never a user JWT, so a user-permission bypass could never
    // succeed here — the Minecraft plugin needs a hard exemption.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val request = context.request()

        request.pause()

        if (!setupManager.isSetupDone()) {
            return InstallationRequired()
        }

        if (!serverAuthProvider.isAuthenticated(context)) {
            return InvalidToken()
        }

        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val sqlClient = databaseManager.getSqlClient()

        val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: return InvalidToken()

        if (!server.permissionGranted) {
            return NeedPermission()
        }

        request.resume()

        val webSocket = request.toWebSocket()

        webSocket.onSuccess {
            CoroutineScope(context.vertx().dispatcher()).launch {
                onConnectionEstablished(context, it)
            }
        }

        webSocket.onFailure {
            if (!context.response().ended()) {
                context.response().end()
            }
        }

        return null
    }

    private suspend fun onConnectionEstablished(context: RoutingContext, serverWebSocket: ServerWebSocket) {
        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val sqlClient = databaseManager.getSqlClient()

        val server = databaseManager.serverDao.getById(serverId, sqlClient)!!
        val remoteAddress = authProvider.getRemoteIP(context)

        server.remoteAddress = remoteAddress
        databaseManager.serverDao.updateStatusById(serverId, ServerStatus.ONLINE, sqlClient)
        databaseManager.serverDao.updateRemoteAddressById(serverId, remoteAddress, sqlClient)

        serverManager.onServerConnect(server, serverWebSocket)

        panelRealtimeHub.notifyServerUpdated(server.id)

        serverWebSocket.textMessageHandler {
            CoroutineScope(context.vertx().dispatcher()).launch {
                serverManager.onServerWrite(it, server)
            }
        }

        serverWebSocket.closeHandler {
            CoroutineScope(context.vertx().dispatcher()).launch {
                onConnectionClosed(server)
            }
        }
    }

    private suspend fun onConnectionClosed(server: Server) {
        val sqlClient = databaseManager.getSqlClient()

        val serverExists = databaseManager.serverDao.existsById(server.id, sqlClient)

        if (serverExists) {
            databaseManager.serverDao.updateStopTimeById(server.id, System.currentTimeMillis(), sqlClient)
            databaseManager.serverDao.updateServerForOfflineById(server.id, sqlClient)
        }

        serverManager.onServerDisconnect(server)

        if (serverExists) {
            panelRealtimeHub.notifyServerUpdated(server.id)
        }
    }
}