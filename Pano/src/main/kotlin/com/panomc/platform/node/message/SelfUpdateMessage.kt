package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Tells a node to replace its own jar and restart (`SELF_UPDATE`).
 *
 * [sha256] is not optional in spirit: the node is being told to download and then execute code, so
 * it verifies the artifact against this digest before swapping anything.
 */
data class SelfUpdateMessage(
    val version: String,
    val url: String,
    val sha256: String
) : NodeMessage
