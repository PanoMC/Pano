package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.message.ServerPluginStateMessage
import com.panomc.platform.server.ServerManager
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Keeps every node's idea of "is this server's plugin connected?" in step with Pano's (§2.4.17 B).
 *
 * One fact that only Pano holds and only the node can act on: the plugin's socket lands here, and
 * the node is the side deciding whether to spend a server list ping on a server every ten seconds.
 * Told wrongly, a node either pings a server that is already reporting a perfect roster from the
 * inside, or stops pinging one that is reporting nothing at all — so it is pushed on both edges
 * and re-sent in full whenever a node reconnects, because a daemon that restarted knows nothing.
 *
 * Every push is best effort. The node's default is "not connected", which is the safe end of being
 * wrong: it pings a server it did not need to rather than showing an empty player list.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerPluginStateService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager
) {
    /** Tells [server]'s node that its plugin just connected or just went away. */
    fun push(server: Server, connected: Boolean) {
        if (!server.isManaged) {
            return
        }

        val nodeId = server.nodeId ?: return
        val uuid = server.uuid ?: return

        nodeManager.sendMessage(nodeId, ServerPluginStateMessage(uuid, connected))
    }

    /** Sends one node the plugin state of every server it owns, after its hello. */
    suspend fun syncNode(nodeId: Long, sqlClient: SqlClient) {
        databaseManager.serverDao.getAllByNodeId(nodeId, sqlClient).forEach { server ->
            push(server, serverManager.isConnected(server.id))
        }
    }
}
