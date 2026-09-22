package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.dto.ConsoleLineData

/**
 * Batch of console lines pushed by a connected server.
 *
 * Everything is nullable because the payload comes from a plugin that may be older or newer than
 * this Pano: a missing field must degrade instead of throwing while decoding the batch.
 */
data class ConsoleLinesEventRequest(
    val lines: List<ConsoleLineData>? = null,
    val dropped: Long? = null
) : ServerEventRequest()
