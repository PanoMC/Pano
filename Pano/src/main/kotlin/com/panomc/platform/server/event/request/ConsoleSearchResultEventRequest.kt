package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.dto.ConsoleSearchLineData

/**
 * A plugin's answer to `CONSOLE_SEARCH`, paired to the request by the inherited `eventId`.
 *
 * The same shape a node answers with: [ok] and [error] (`BAD_QUERY`, `BAD_CURSOR`, `READ_FAILED`,
 * or `DISABLED` together with [disabled] when console capture is switched off in the plugin's
 * config), the page's [lines] each carrying the file it came from, the [cursor] to ask with next
 * and whether the search is [done], and the progress figures the panel shows. Everything nullable,
 * because the plugin may be older or newer than this Pano and a missing field must degrade rather
 * than fail the decode.
 */
data class ConsoleSearchResultEventRequest(
    val ok: Boolean? = null,
    val error: String? = null,
    val lines: List<ConsoleSearchLineData>? = null,
    val cursor: String? = null,
    val done: Boolean? = null,
    val scannedFiles: Int? = null,
    val totalFiles: Int? = null,
    val scannedBytes: Long? = null,
    val capped: Boolean? = null,
    val disabled: Boolean? = null
) : ServerEventRequest()
