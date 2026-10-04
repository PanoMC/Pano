package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NeedPermission
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.node.NodeAuthProvider
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeStatus
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.setup.SetupManager
import io.vertx.core.http.ServerWebSocket
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The live node channel (`GET /api/node/connection`).
 *
 * Same shape as the Minecraft server socket: the request is paused until the upgrade, the node
 * authenticates with its own bearer token, and every frame after that is AES-256-GCM. A node that
 * has not been approved is refused here rather than at pairing time, so an admin accepting it in
 * the panel is all it takes for the daemon's own reconnect loop to get in.
 */
@Endpoint
class NodeConnectAPI(
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val nodeAuthProvider: NodeAuthProvider,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/node/connection", RouteType.GET))

    // Authenticates with a node token, never a user JWT.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val request = context.request()

        request.pause()

        if (!setupManager.isSetupDone()) {
            return InstallationRequired()
        }

        if (!nodeAuthProvider.isAuthenticated(context)) {
            return InvalidToken()
        }

        val nodeId = nodeAuthProvider.getNodeIdFromRoutingContext(context) ?: return InvalidToken()

        val sqlClient = databaseManager.getSqlClient()

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: return InvalidToken()

        if (!node.approved) {
            return NeedPermission()
        }

        request.resume()

        val webSocket = request.toWebSocket()

        webSocket.onSuccess {
            // Same as ServerConnectAPI: the daemon speaks the moment the upgrade completes, and a
            // frame that lands before the handler is set is dropped, not queued. Paused until wired.
            it.pause()

            CoroutineScope(context.vertx().dispatcher()).launch {
                onConnectionEstablished(context, nodeId, it)
            }
        }

        webSocket.onFailure {
            if (!context.response().ended()) {
                context.response().end()
            }
        }

        return null
    }

    private suspend fun onConnectionEstablished(context: RoutingContext, nodeId: Long, webSocket: ServerWebSocket) {
        val sqlClient = databaseManager.getSqlClient()

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: run {
            webSocket.close()

            return
        }

        val remoteAddress = authProvider.getRemoteIP(context)
        val now = System.currentTimeMillis()

        node.remoteAddress = remoteAddress
        node.status = NodeStatus.ONLINE
        node.lastSeen = now

        databaseManager.nodeDao.updateStatusById(nodeId, NodeStatus.ONLINE, now, sqlClient)
        databaseManager.nodeDao.updateRemoteAddressById(nodeId, remoteAddress, sqlClient)

        nodeManager.onNodeConnect(node, webSocket)

        panelRealtimeHub.notifyNodeUpdated(nodeId)

        // The node's stdout pipe now outranks the plugin's appender on every server it hosts, so
        // any console currently fed by a plugin is switched over and the plugin is told to stop.
        panelRealtimeHub.onNodeConsoleAvailabilityChanged(nodeId)

        // Nothing on the row changed, but every one of this node's servers can suddenly do things
        // it could not a second ago, so their `features` have to go out (SM-52).
        panelRealtimeHub.notifyNodeServersUpdated(nodeId)

        webSocket.textMessageHandler {
            CoroutineScope(context.vertx().dispatcher()).launch {
                nodeManager.onNodeWrite(it, node)
            }
        }

        webSocket.closeHandler {
            CoroutineScope(context.vertx().dispatcher()).launch {
                onConnectionClosed(node, webSocket)
            }
        }

        // Only now: whatever the daemon sent while this was being wired is delivered in order.
        webSocket.resume()
    }

    private suspend fun onConnectionClosed(node: Node, webSocket: ServerWebSocket) {
        val sqlClient = databaseManager.getSqlClient()

        val nodeExists = databaseManager.nodeDao.existsById(node.id, sqlClient)

        if (nodeExists) {
            databaseManager.nodeDao.updateStatusById(
                node.id,
                NodeStatus.OFFLINE,
                System.currentTimeMillis(),
                sqlClient
            )
        }

        nodeManager.onNodeDisconnect(node, webSocket)

        if (nodeExists) {
            panelRealtimeHub.notifyNodeUpdated(node.id)

            // The better stream is gone. A game that is still running can still report its own
            // console through the plugin, so the fallback is asked to take over rather than
            // leaving anyone watching with a window that has simply stopped.
            panelRealtimeHub.onNodeConsoleAvailabilityChanged(node.id)

            // Same in reverse: the panel has to stop offering what only this node could do.
            panelRealtimeHub.notifyNodeServersUpdated(node.id)
        }
    }
}
