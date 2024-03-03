package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NeedPermission
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.server.ServerAuthProvider
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.setup.SetupManager
import io.vertx.core.http.ServerWebSocket
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Endpoint
class ServerConnectAPI(
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val serverAuthProvider: ServerAuthProvider,
    private val serverManager: ServerManager
) : Api() {
    override val paths = listOf(Path("/api/server/connection", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

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

        databaseManager.serverDao.updateStatusById(serverId, ServerStatus.ONLINE, sqlClient)

        serverManager.onServerConnect(server, serverWebSocket)

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
    }
}