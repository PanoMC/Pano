package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.dto.NodeMetricSample
import com.panomc.platform.node.event.request.NodeMetricsEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.alert.AlertManager

/**
 * Host metrics from a node (`NODE_METRICS`), kept in memory only.
 *
 * Nothing is written to the database: unlike per-server metrics there is no chart to draw yet, and
 * a row every ten seconds per node would be a lot of writes to support one number on one page.
 * The sample also refreshes `lastSeen`, which makes it double as an application-level "the node is
 * still doing its job" signal on top of the WebSocket heartbeat.
 */
@Event
class NodeMetricsEvent(
    private val nodeManager: NodeManager,
    private val databaseManager: DatabaseManager,
    private val alertManager: AlertManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : NodeEvent<NodeMetricsEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: NodeMetricsEventRequest, node: Node): NodeEventResponse? {
        val now = System.currentTimeMillis()

        val sample = NodeMetricSample(
            t = request.t?.takeIf { it > 0 } ?: now,
            cpu = request.cpu,
            memUsed = (request.memUsed ?: 0).coerceAtLeast(0),
            memTotal = (request.memTotal ?: 0).coerceAtLeast(0),
            diskUsed = (request.diskUsed ?: 0).coerceAtLeast(0),
            diskTotal = (request.diskTotal ?: 0).coerceAtLeast(0),
            // A negative rate is a broken sender, not traffic.
            netRxBps = request.netRxBps?.takeIf { it >= 0 },
            netTxBps = request.netTxBps?.takeIf { it >= 0 }
        )

        nodeManager.setLatestMetrics(node.id, sample)

        // Straight to whoever watches this node (SM-65): at a half-second rate this frame is the
        // nodes page's live CPU / RAM / disk / network, and the row itself has not changed.
        panelRealtimeHub.pushNodeMetrics(node.id, sample)

        // The row and the alerts keep the default ten-second cadence however fast somebody is
        // watching (SM-65): a node reporting twice a second is not twice a second's news for the
        // database, and a disk crossing 90 % does not need to be checked more often than before.
        if (!isDue(node.lastSeen, now)) {
            return null
        }

        node.lastSeen = now

        databaseManager.nodeDao.updateLastSeenById(node.id, now, databaseManager.getSqlClient())

        // Every frame that gets here is evaluated; almost none of them say anything, because a disk
        // crossing 90 % is reported once every six hours rather than every ten seconds.
        alertManager.onNodeMetrics(node, sample.diskUsed, sample.diskTotal, databaseManager.getSqlClient())

        return null
    }

    companion object {
        /** Slightly under the node's default ten seconds, so a default-rate frame is never skipped. */
        const val PERSIST_INTERVAL_MS = 9_000L

        /** Whether a frame at [now] should touch the row, given the last one that did at [lastSeen]. */
        fun isDue(lastSeen: Long, now: Long): Boolean = now - lastSeen >= PERSIST_INTERVAL_MS || now < lastSeen
    }
}
