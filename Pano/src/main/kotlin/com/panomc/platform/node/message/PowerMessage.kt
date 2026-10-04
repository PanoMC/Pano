package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Asks a node to change a managed server's process state (`POWER`).
 *
 * Unlike the plugin's power message this really is process control: START and KILL exist here
 * because a node owns the process, and STOP is a graceful stop (console `stop`, then SIGTERM, then
 * kill) rather than a request the game server may decline.
 */
data class PowerMessage(
    val serverUuid: String,
    val action: String,
    val requestId: String,
    val issuedBy: String
) : NodeMessage
