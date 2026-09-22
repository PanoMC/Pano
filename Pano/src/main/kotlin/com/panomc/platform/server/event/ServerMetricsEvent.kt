package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.dto.ServerMetricPlayerData
import com.panomc.platform.server.dto.ServerMetricSample
import com.panomc.platform.server.event.request.ServerMetricsEventRequest
import com.panomc.platform.server.metrics.ServerDiskUsageStore
import com.panomc.platform.server.players.ServerPlayerCommandComposer

/**
 * Receives a performance sample from a connected server (`SERVER_METRICS`).
 *
 * The sample replaces that server's latest one in memory and is pushed to whoever is watching it.
 * Nothing is written to the database here: the per-minute history is written by
 * `ServerMetricsRecorder` from exactly this value, so a server sending every 10 seconds costs no
 * database traffic at all.
 */
@Event
class ServerMetricsEvent(
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val alertManager: AlertManager,
    private val databaseManager: com.panomc.platform.db.DatabaseManager,
    private val serverDiskUsageStore: ServerDiskUsageStore
) : ServerEvent<ServerMetricsEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ServerMetricsEventRequest, server: Server): ServerEventResponse? {
        val now = System.currentTimeMillis()

        // Only for the disk figure below: everything else in a plugin sample is measured inside
        // the game, where there is nothing a previous sample knows better.
        val existing = serverManager.getLatestMetrics(server.id)

        val sample = ServerMetricSample(
            t = request.t?.takeIf { it > 0 } ?: now,
            // A tick average only makes sense as the 1/5/15 minute triple; anything else is a
            // broken sender and is treated as "not reported" rather than half-plotted.
            tps = request.tps?.takeIf { it.size == TPS_AVERAGE_COUNT },
            mspt = request.mspt,
            memUsed = (request.memUsed ?: 0L).coerceAtLeast(0L),
            memMax = (request.memMax ?: 0L).coerceAtLeast(0L),
            cpu = request.cpu?.coerceIn(0.0, 100.0),
            playerCount = (request.playerCount ?: 0L).coerceAtLeast(0L),
            maxPlayerCount = (request.maxPlayerCount ?: 0L).coerceAtLeast(0L),
            players = (request.players ?: emptyList())
                .take(MAX_ROSTER_SIZE)
                .map { sanitize(it) },
            // The node measures the same directory from outside the process and its figure is
            // the host's truth (§2.4.18 B), so this one only ever fills a gap — and a sample with
            // no figure at all never erases the one already there, which is what keepingDiskOf
            // below is for.
            diskUsed = request.diskUsed?.coerceAtLeast(0L),
            diskTotal = request.diskTotal?.coerceAtLeast(0L)
        ).keepingDiskOf(existing).keepingNetOf(existing).keepingProcessOf(existing)

        serverManager.setLatestMetrics(server.id, sample)

        panelRealtimeHub.pushMetrics(server.id, sample, server.nodeId)

        reconcileRoster(server, sample, existing, now)

        // On the row as well, so a linked server's size survives a Pano restart and stays there
        // while the server is off — the same place the node writes its own measurement to, which
        // is why this stores the figure that won rather than the one this plugin reported.
        // Written only when it changed (§2.4.18 B).
        serverDiskUsageStore.persist(server, sample.diskUsed, sample.diskTotal, databaseManager.getSqlClient())

        // The one-minute average, which is the reading that reflects how the server feels right
        // now; the five and fifteen minute ones are still recovering long after it has.
        alertManager.onServerTps(server, sample.tps?.firstOrNull(), databaseManager.getSqlClient())

        return null
    }

    /**
     * Drops `server_player` rows for players the plugin no longer reports.
     *
     * The table is written by join and quit events, and a quit that never arrived leaves a row
     * behind for good: the server was stopped or killed while the player was on (the quit fires
     * as the plugin is going down, too late to reach Pano), or the connection dropped. The
     * players page would then list someone who is not there. The sample is the plugin's full
     * roster, so it settles the question - but only for rows older than [ROSTER_GRACE_MILLIS],
     * because a join can land a moment before the first sample that includes it.
     *
     * Only run when the set of reported players changed, or for the first sample Pano has seen
     * from this server, so a half-second cadence does not become a query every half second.
     */
    private suspend fun reconcileRoster(server: Server, sample: ServerMetricSample, previous: ServerMetricSample?, now: Long) {
        val reported = sample.players.mapTo(HashSet()) { it.uuid.lowercase() }

        if (previous != null && previous.players.mapTo(HashSet()) { it.uuid.lowercase() } == reported) {
            return
        }

        val sqlClient = databaseManager.getSqlClient()

        val stale = databaseManager.serverPlayerDao.getAllByServerId(server.id, sqlClient).filter {
            it.uuid.toString().lowercase() !in reported && it.loginTime < now - ROSTER_GRACE_MILLIS
        }

        if (stale.isEmpty()) {
            return
        }

        stale.forEach { databaseManager.serverPlayerDao.deleteByUsernameAndServerId(it.username, server.id, sqlClient) }

        panelRealtimeHub.notifyServerPlayersUpdated(server.id)
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(player: ServerMetricPlayerData) = ServerMetricPlayerData(
        uuid = if (player.uuid == null) "" else player.uuid,
        username = if (player.username == null) "" else player.username,
        ping = player.ping.coerceAtLeast(0L),
        op = player.op,
        whitelisted = player.whitelisted,
        // Only the four vanilla names travel on: the panel builds an i18n key from this.
        gamemode = player.gamemode?.lowercase()?.takeIf { it in ServerPlayerCommandComposer.GAME_MODES }
    )

    companion object {
        private const val TPS_AVERAGE_COUNT = 3

        // No Minecraft server runs more players than this on one instance; a bigger list is a
        // broken sender, and this bounds what one sample can cost in memory.
        private const val MAX_ROSTER_SIZE = 2000

        /** How old a roster row must be before a sample that lacks its player may remove it. */
        private const val ROSTER_GRACE_MILLIS = 15_000L
    }
}
