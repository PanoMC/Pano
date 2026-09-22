package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Asks a node to report its host metrics (`NODE_METRICS`) every [intervalMs]
 * (`SET_NODE_METRICS_INTERVAL`, SM-65, §2.4.30).
 *
 * Sent while somebody watches the nodes page faster than every ten seconds and re-sent every minute;
 * the node keeps the rate for ninety seconds without hearing it again. Only sent to nodes that
 * announced protocol 4 — an older one would log every renewal as an unknown message.
 */
data class SetNodeMetricsIntervalMessage(
    val intervalMs: Long
) : NodeMessage
