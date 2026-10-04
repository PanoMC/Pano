package com.panomc.platform.server.metrics

import com.panomc.platform.node.dto.NodeMetricSample
import com.panomc.platform.server.dto.ServerMetricSample
import io.vertx.core.json.JsonObject

/**
 * The live sample as the two metrics endpoints hand it to the panel (§2.4.18 B).
 *
 * One thing is added to it that the sample itself does not carry: the total memory of the host the
 * server runs on. The panel's RAM gauge needs a denominator, and for a node-sourced sample the one
 * on the sample is the wrong number — `memUsed`/`memRss` there is the process's *resident set*
 * while `memMax` is the Xmx it was launched with, and a JVM's RSS is always larger than its heap
 * limit (metaspace, thread stacks, GC structures, the JVM itself). Dividing one by the other reads
 * "100 %" on every healthy server, which is worse than showing nothing. Against the host's memory
 * the same figure means what an operator expects: how much of this machine this server is using.
 *
 * It is injected here rather than stored on [ServerMetricSample] on purpose. It is not something
 * the server reported, it changes only when the hardware does, and it belongs to the node rather
 * than to the sample — so it is not in the sample's state, not in the per-minute history, and not
 * on the server row either.
 */
object ServerLatestMetrics {
    /**
     * Total memory of the host a server's node last reported, or null when there is nothing to
     * divide by.
     *
     * Null covers every case the panel must draw as an empty ring rather than a wrong one: a
     * linked server with no node at all, a node that is offline (its sample is dropped the moment
     * it disconnects), one that has not reported yet, and one whose host could not be read — the
     * node metric sample stores that last case as a zero.
     */
    fun hostMemTotal(nodeMetrics: NodeMetricSample?): Long? = nodeMetrics?.memTotal?.takeIf { it > 0 }

    /** [sample] as JSON with [hostMemTotal] beside it, or null when there is no sample at all. */
    fun latestJson(sample: ServerMetricSample?, hostMemTotal: Long?): JsonObject? =
        sample?.toJsonObject()?.put("hostMemTotal", hostMemTotal)
}
