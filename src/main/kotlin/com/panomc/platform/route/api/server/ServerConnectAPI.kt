package com.panomc.platform.route.api.server

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
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

    override suspend fun handler(context: RoutingContext): Result? {
        val request = context.request()

        request.pause()

        if (!setupManager.isSetupDone()) {
            return Error(ErrorCode.INSTALLATION_REQUIRED)
        }

        if (!serverAuthProvider.isAuthenticated(context)) {
            return Error(ErrorCode.INVALID_TOKEN)
        }

        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val server = databaseManager.serverDao.getById(serverId, sqlConnection) ?: return Error(ErrorCode.INVALID_TOKEN)

        if (!server.permissionGranted) {
            return Error(ErrorCode.NEED_PERMISSION)
        }

        databaseManager.closeConnection(sqlConnection)

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

        val sqlConnection = createConnection(context)

        val server = databaseManager.serverDao.getById(serverId, sqlConnection)!!

        databaseManager.serverDao.updateStatusById(serverId, ServerStatus.ONLINE, sqlConnection)

        databaseManager.closeConnection(sqlConnection)

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
        val sqlConnection = databaseManager.createConnection()

        val serverExists = databaseManager.serverDao.existsById(server.id, sqlConnection)

        if (serverExists) {
            databaseManager.serverDao.updateServerForOfflineById(server.id, sqlConnection)
        }

        databaseManager.closeConnection(sqlConnection)

        serverManager.onServerDisconnect(server)
    }
}