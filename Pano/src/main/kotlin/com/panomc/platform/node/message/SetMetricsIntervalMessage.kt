package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Asks a node to sample one managed server's process at [intervalMs] (`SET_METRICS_INTERVAL`,
 * §2.4.23 A).
 *
 * Sent while somebody watches that server's vitals faster than every ten seconds, and re-sent every
 * minute: the node keeps the rate for ninety seconds without hearing it again, then returns to its
 * default on its own. A node too old to know the message ignores it and keeps reporting every ten.
 */
data class SetMetricsIntervalMessage(
    val serverUuid: String,
    val intervalMs: Long
) : NodeMessage
