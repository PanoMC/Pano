package com.panomc.platform.node

/**
 * Whether a node's WebSocket connection to Pano is live.
 *
 * Nodes are forced to [OFFLINE] on boot, the same way servers are: a status left over from before
 * a restart says nothing about a socket this process never had.
 */
enum class NodeStatus {
    ONLINE,
    OFFLINE
}
