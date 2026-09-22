package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.dto.ServerMetricPlayerData

/**
 * Periodic performance sample pushed by a connected server (`SERVER_METRICS`).
 *
 * Every field is optional: a proxy has no tick loop, some platforms cannot read process CPU, and
 * an older or newer plugin may simply not send a field. Missing values degrade to null rather
 * than failing the whole sample.
 */
data class ServerMetricsEventRequest(
    val t: Long? = null,
    val tps: List<Double>? = null,
    val mspt: Double? = null,
    val memUsed: Long? = null,
    val memMax: Long? = null,
    val cpu: Double? = null,
    val playerCount: Long? = null,
    val maxPlayerCount: Long? = null,
    val players: List<ServerMetricPlayerData>? = null,
    /**
     * Bytes the server's own directory takes on disk, as the plugin measured it (§2.4.18 A).
     *
     * Measured on the plugin's async executor and cached for five minutes over there, so it is
     * null on the first samples after a start and from any plugin too old to know the field —
     * "not measured", never zero.
     */
    val diskUsed: Long? = null,
    /**
     * Total size of the partition the server directory is on, as the plugin read it (§2.4.18,
     * revision of 2026-09-22).
     *
     * The gauge's denominator, and cheap on either side, so unlike [diskUsed] it needs no cache
     * and is usually present from the first sample.
     */
    val diskTotal: Long? = null
) : ServerEventRequest()
