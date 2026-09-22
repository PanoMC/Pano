package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Turns a managed server's stdout stream on or off (`CONSOLE_STREAM`).
 *
 * Same subscriber logic as the plugin stream: on for the first panel watching, off a grace period
 * after the last one leaves. The node keeps its own ring buffer either way, so switching it back
 * on replays the recent past instead of starting from silence.
 */
data class ConsoleStreamMessage(
    val serverUuid: String,
    val enabled: Boolean
) : NodeMessage
