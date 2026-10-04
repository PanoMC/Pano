package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.event.request.ServerConsoleLinesEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.console.ConsoleSpans
import com.panomc.platform.server.dto.ConsoleLineData

/**
 * Console output a node read from a managed server's process (`SERVER_CONSOLE_LINES`).
 *
 * Lands in the very same per-server ring buffer as the plugin's stream, tagged `src = "node"`.
 * Merging rather than keeping two buffers is deliberate: the interesting moments — the boot before
 * the plugin loads, and the stack trace after it has gone — only exist in this stream, and reading
 * them interleaved with the plugin's lines is the whole point. Duplicate lines are a known
 * consequence and are left for the panel to fold; losing ordering would be worse.
 */
@Event
class ServerConsoleLinesEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : NodeEvent<ServerConsoleLinesEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: ServerConsoleLinesEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return null

        val now = System.currentTimeMillis()

        val lines = (request.lines ?: emptyList())
            .take(MAX_LINES_PER_BATCH)
            .map { sanitize(it, now) }

        val dropped = (request.dropped ?: 0L).coerceAtLeast(0L)

        if (lines.isEmpty() && dropped == 0L) {
            return null
        }

        serverManager.getConsoleBuffer(server.id).add(lines, dropped)

        panelRealtimeHub.pushConsoleLines(server.id, lines, dropped)

        return null
    }

    // Gson fills missing fields with the data class defaults, but an explicit JSON null lands in
    // the field regardless of the Kotlin type, so the nulls are handled rather than trusted.
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(line: ConsoleLineData, now: Long): ConsoleLineData {
        val rawMessage = if (line.m == null) "" else line.m
        val rawLevel = if (line.l == null) ConsoleLineData.UNKNOWN_LEVEL else line.l.uppercase()

        val message = if (rawMessage.length > ConsoleLineData.MAX_MESSAGE_LENGTH)
            rawMessage.substring(0, ConsoleLineData.MAX_MESSAGE_LENGTH)
        else
            rawMessage

        return ConsoleLineData(
            t = if (line.t <= 0) now else line.t,
            l = if (rawLevel in ConsoleLineData.KNOWN_LEVELS) rawLevel else ConsoleLineData.UNKNOWN_LEVEL,
            m = message,
            src = ConsoleLineData.SRC_NODE,
            // Judged against the text as Pano keeps it, so a span can never point past it (§2.4.21 D).
            c = ConsoleSpans.validate(line.c, message)
        )
    }

    companion object {
        // Same ceiling as the plugin stream: anything beyond one batch's worth is a broken or
        // hostile sender, not a busy server.
        private const val MAX_LINES_PER_BATCH = 500
    }
}
