package com.panomc.platform.server.event.request

import com.panomc.platform.server.RawPayloadCarrier
import com.panomc.platform.server.ServerEventRequest
import io.vertx.core.json.JsonObject

/**
 * A plugin's answer to any agent-lite request (`FILE_RESULT`), paired by the inherited `eventId`.
 *
 * One reply name for every request, exactly as the node protocol does it: what the body means is
 * decided by which request it answers, so only [ok] and [error] are declared here and the rest is
 * read out of [raw] by whoever asked.
 */
data class FileResultEventRequest(
    val ok: Boolean? = null,
    val error: String? = null
) : ServerEventRequest(), RawPayloadCarrier {
    override var raw: JsonObject? = null
}
