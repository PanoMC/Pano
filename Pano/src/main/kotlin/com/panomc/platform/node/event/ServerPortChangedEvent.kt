package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.event.request.ServerPortChangedEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.dto.ConsoleLineData
import org.slf4j.Logger

/**
 * A managed server was installed on a different port than Pano asked for (`SERVER_PORT_CHANGED`).
 *
 * Pano allocates the port when the row is created, which is the only way two of its own installs
 * cannot pick the same number. What it cannot do is see the host: on a real machine 25565 is very
 * often already bound by something that has nothing to do with Pano, and a server installed onto it
 * comes up, fails to bind and exits — historically as a clean STOPPED nobody could explain.
 *
 * So the node probes the number before it writes `server.properties`, takes the next free one in
 * its range when it has to, and says so here. The row then carries the address the server actually
 * listens on, which is what the panel shows and what players are handed.
 */
@Event
class ServerPortChangedEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverManager: ServerManager,
    private val logger: Logger
) : NodeEvent<ServerPortChangedEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: ServerPortChangedEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        // The choke point that stops a node speaking about another node's server.
        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return null

        val port = request.port?.takeIf { it in 1..MAX_PORT } ?: return null

        if (port == server.gamePort && port == server.port) {
            return null
        }

        databaseManager.serverDao.updateGamePortById(server.id, port, sqlClient)

        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON_LENGTH)

        logger.warn(
            "Node ${node.id} installed server ${server.id} on port $port instead of " +
                "${request.requestedPort ?: server.gamePort}" + (reason?.let { ": $it" } ?: ".")
        )

        panelRealtimeHub.notifyServerUpdated(server.id)

        console(
            server.id,
            "Pano: this server is on port $port, not ${request.requestedPort ?: "the one it was given"}" +
                (reason?.let { " ($it)" } ?: "") + ".",
            "WARN"
        )

        return null
    }

    /** Puts one Pano-authored line into the server's console, live and in the history alike. */
    private fun console(serverId: Long, message: String, level: String) {
        val line = ConsoleLineData(
            t = System.currentTimeMillis(),
            l = level,
            m = message,
            src = ConsoleLineData.SRC_NODE
        )

        serverManager.getConsoleBuffer(serverId).add(listOf(line), 0)

        panelRealtimeHub.pushConsoleLines(serverId, listOf(line), 0)
    }

    companion object {
        private const val MAX_PORT = 65535
        private const val MAX_REASON_LENGTH = 200
    }
}
