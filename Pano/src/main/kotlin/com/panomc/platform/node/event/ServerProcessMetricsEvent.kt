package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.event.request.ServerProcessMetricsEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.dto.ServerMetricPlayerData
import com.panomc.platform.server.dto.ServerMetricSample
import com.panomc.platform.server.metrics.ServerDiskUsageStore

/**
 * The operating system's view of a managed server process (`SERVER_PROCESS_METRICS`).
 *
 * Merged into the same latest-sample slot the plugin writes rather than kept apart, because the
 * panel shows one row of numbers per server and nobody cares which half came from where. What the
 * two halves measure is genuinely different though — the plugin reports heap, this reports
 * resident memory of the whole JVM — so the fields sit next to each other instead of overwriting.
 *
 * Since §2.4.17 B the frame also carries what a server list ping answered, and that half is only
 * sent when there is nobody better to ask. So when a plugin sample is there, this only ever
 * refreshes the two process numbers; when there is none, the whole sample is built from what the
 * node saw, marked [ServerMetricSample.SOURCE_NODE] so the panel can say where the figures came
 * from and never pass a twelve-name ping sample off as a roster.
 *
 * Since §2.4.18 A the same message is also how a *stopped* server reports the size of its
 * directory, which is a frame with nothing else in it; see [ServerProcessMetricsEventRequest.isDiskOnly].
 */
@Event
class ServerProcessMetricsEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverDiskUsageStore: ServerDiskUsageStore
) : NodeEvent<ServerProcessMetricsEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: ServerProcessMetricsEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return null

        val now = System.currentTimeMillis()
        val timestamp = request.t?.takeIf { it > 0 } ?: now

        val existing = serverManager.getLatestMetrics(server.id)

        // A server that is not running still has a directory, and that is the whole of what this
        // frame says (§2.4.18 A). It must not create a sample and must not refresh the one that
        // is there beyond the one number, or a stopped server would look alive to the panel and
        // get a row a minute in its history saying so.
        if (request.isDiskOnly) {
            existing?.let {
                serverManager.setLatestMetrics(
                    server.id,
                    it.withDiskUsage(request.diskBytes, request.diskTotalBytes)
                )
            }

            serverDiskUsageStore.persist(server, request.diskBytes, request.diskTotalBytes, sqlClient)

            return null
        }

        // The plugin's sample wins wherever it exists: it is measured inside the game, and only
        // the two process fields are something it could not have seen.
        val host = nodeManager.getLatestMetrics(node.id)

        val merged = if (existing != null && existing.source == ServerMetricSample.SOURCE_PLUGIN) {
            existing.withProcessMetrics(
                timestamp,
                request.processCpu,
                request.residentBytes,
                request.diskBytes,
                request.diskTotalBytes
            )
        } else {
            nodeSample(request, server.memoryMb, server.playerCount, server.maxPlayerCount, timestamp, existing)
        }.withNetwork(
            // The server's own traffic when its runtime could count it; otherwise the node's, which
            // is every server on that host together and is labelled so (§2.4.22 A).
            request.netRxBps?.takeIf { it >= 0 },
            request.netTxBps?.takeIf { it >= 0 },
            host?.netRxBps,
            host?.netTxBps
        )

        serverManager.setLatestMetrics(server.id, merged)

        panelRealtimeHub.pushMetrics(server.id, merged, server.nodeId)

        // The row too, so the figures are still there after a restart and for as long as the
        // server stays off. Written only when they changed.
        serverDiskUsageStore.persist(server, request.diskBytes, request.diskTotalBytes, sqlClient)

        return null
    }

    /**
     * A whole sample built out of what the node can see.
     *
     * `memUsed` is the process's resident set and `memMax` is the memory the server was given,
     * because that is the pair the memory chart draws — a managed server with no plugin in it gets
     * a real memory graph rather than a flat zero. The ping's counts are exact; its names are not,
     * which is why they arrive with no uuid and no ping time and why the panel labels them.
     */
    private fun nodeSample(
        request: ServerProcessMetricsEventRequest,
        memoryMb: Int?,
        knownPlayerCount: Long,
        knownMaxPlayerCount: Long,
        timestamp: Long,
        existing: ServerMetricSample?
    ): ServerMetricSample {
        val resident = request.residentBytes

        return ServerMetricSample(
            t = timestamp,
            tps = null,
            mspt = null,
            memUsed = resident ?: 0,
            memMax = memoryMb?.takeIf { it > 0 }?.let { it.toLong() * BYTES_PER_MB } ?: 0,
            cpu = null,
            playerCount = request.playerCount ?: knownPlayerCount,
            maxPlayerCount = request.maxPlayers ?: knownMaxPlayerCount,
            players = request.playerSample
                .orEmpty()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
                .take(ServerMetricSample.MAX_PLAYER_SAMPLE)
                .map { ServerMetricPlayerData(username = it) }
                .toList(),
            processCpu = request.processCpu,
            memRss = resident,
            source = ServerMetricSample.SOURCE_NODE,
            motd = request.motd?.take(MAX_MOTD_LENGTH),
            versionName = request.versionName?.take(MAX_NAME_LENGTH),
            // A frame sent between two walks of the directory says nothing about its size, so the
            // last figure stands rather than the chart dropping to a dash every other tick.
            diskUsed = request.diskBytes ?: existing?.diskUsed,
            diskTotal = request.diskTotalBytes ?: existing?.diskTotal,
            diskUsedFromNode = request.diskBytes != null || existing?.diskUsedFromNode == true,
            diskTotalFromNode = request.diskTotalBytes != null || existing?.diskTotalFromNode == true
        )
    }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MAX_NAME_LENGTH = 64
        private const val MAX_MOTD_LENGTH = 256
    }
}
