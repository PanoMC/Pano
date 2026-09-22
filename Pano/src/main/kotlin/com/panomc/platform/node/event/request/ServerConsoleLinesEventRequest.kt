package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest
import com.panomc.platform.server.dto.ConsoleLineData

/**
 * A batch of stdout/stderr lines the node read from a managed server's process.
 *
 * Same shape as the plugin's `CONSOLE_LINES` so both streams can land in one buffer; the `src`
 * field of each line is set by Pano afterwards and anything the node puts there is discarded.
 */
data class ServerConsoleLinesEventRequest(
    val serverUuid: String? = null,
    val lines: List<ConsoleLineData>? = null,
    val dropped: Long? = null
) : NodeEventRequest()
