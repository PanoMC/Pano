package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.dto.ConsoleLineData

/**
 * A plugin's answer to `CONSOLE_HISTORY`, paired to the request by the inherited `eventId`.
 *
 * Everything is nullable because the payload comes from a plugin that may be older or newer than
 * this Pano: a missing field must degrade instead of throwing while decoding the page. [disabled]
 * is the plugin saying console capture is switched off in its own config, which is a legitimate
 * empty answer rather than a server with no history.
 */
data class ConsoleHistoryResultEventRequest(
    val lines: List<ConsoleLineData>? = null,
    val hasMore: Boolean? = null,
    val disabled: Boolean? = null
) : ServerEventRequest()
