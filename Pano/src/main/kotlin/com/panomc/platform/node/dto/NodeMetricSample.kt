package com.panomc.platform.node.dto

import io.vertx.core.json.JsonObject

/**
 * The most recent host metrics a node reported.
 *
 * Kept in memory only, one per node: unlike per-server metrics there is nothing to chart here yet,
 * this is what the nodes page shows as "right now". It is dropped when the node disconnects, so an
 * offline node never shows a CPU figure from before it went away.
 */
data class NodeMetricSample(
    val t: Long,
    val cpu: Double?,
    val memUsed: Long,
    val memTotal: Long,
    val diskUsed: Long,
    val diskTotal: Long,
    /**
     * The host's traffic in bytes per second (§2.4.22 A), or null when the node could not measure
     * it. Also what a server on this node falls back to when it cannot count its own.
     */
    val netRxBps: Long? = null,
    val netTxBps: Long? = null
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("t", t)
        .put("cpu", cpu)
        .put("memUsed", memUsed)
        .put("memTotal", memTotal)
        .put("diskUsed", diskUsed)
        .put("diskTotal", diskTotal)
        .put("netRxBps", netRxBps)
        .put("netTxBps", netTxBps)
}
