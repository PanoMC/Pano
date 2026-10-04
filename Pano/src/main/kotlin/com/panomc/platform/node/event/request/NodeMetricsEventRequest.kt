package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/** Host metrics a node reports every ten seconds while connected. */
data class NodeMetricsEventRequest(
    val t: Long? = null,
    val cpu: Double? = null,
    val memUsed: Long? = null,
    val memTotal: Long? = null,
    val diskUsed: Long? = null,
    val diskTotal: Long? = null,
    /**
     * The host's traffic in bytes per second, summed over every interface but loopback
     * (§2.4.22 A). Null on the first tick, after a counter reset and off Linux.
     */
    val netRxBps: Long? = null,
    val netTxBps: Long? = null
) : NodeEventRequest()
