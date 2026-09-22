package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerRequestMessage
import io.vertx.core.json.JsonObject

/**
 * A node message sent down a plugin socket unchanged (§2.4.17 C).
 *
 * Every other message in this package is a class whose name *is* the wire name. These two are the
 * exception on purpose: the agent-lite surface is defined as the node's shapes verbatim, so
 * duplicating nine `FILE_*` classes here would create a second definition that could drift from
 * the first. The event name and the payload are carried instead, and [encode] writes exactly what
 * the node socket would have carried.
 */
class RelayServerRequestMessage(
    private val event: String,
    private val payload: JsonObject
) : ServerRequestMessage() {
    override fun getResponseName() = event

    override fun encode(): String = payload.copy()
        .put("event", event)
        .put("eventId", eventId)
        .encode()
}

/** [RelayServerRequestMessage] for the pushes nobody waits on, such as `BACKUP_CREATE`. */
class RelayServerMessage(
    private val event: String,
    private val payload: JsonObject
) : PlatformMessage {
    override fun getResponseName() = event

    override fun encode(): String = payload.copy().put("event", event).encode()
}
