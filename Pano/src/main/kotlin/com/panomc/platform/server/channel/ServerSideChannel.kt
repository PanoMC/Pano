package com.panomc.platform.server.channel

import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.NodeRequestMessage
import com.panomc.platform.server.feature.ServerFeatureSource
import io.vertx.core.json.JsonObject

/**
 * One way of reaching a server's own machine, whichever side of it Pano can get to (SM-52).
 *
 * The node and the Pano plugin can both read the server's directory, take a backup, install a jar
 * and run a schedule, and §2.4.17 C is explicit that they do it by answering *the same messages*:
 * the plugin implements the node's `FILE_*`, `BACKUP_*`, `INSTALL_PLUGIN` and `SYNC_SCHEDULES`
 * shapes verbatim. So the message stays a [NodeRequestMessage] here and only the delivery changes
 * — no second set of classes to keep in step with the first, and no endpoint that has to know
 * which side answered it.
 *
 * [nodeId] is null on the plugin channel and is what the few genuinely node-bound things ask for:
 * a transfer ticket is issued to a node and a task row belongs to one.
 */
interface ServerSideChannel {
    /** Which side this channel reaches, for the panel and for the error when it cannot. */
    val source: ServerFeatureSource

    /** The uuid every message names the server by, on both protocols. */
    val serverUuid: String

    /** The node behind this channel, or null when the plugin is the one answering. */
    val nodeId: Long?

    /** Sends [message] and suspends until the other side answers with its `{ ok, … }` payload. */
    suspend fun request(message: NodeRequestMessage, timeoutMs: Long): JsonObject

    /** Pushes [message] with nothing to wait for. Throws when it could not be handed over. */
    fun send(message: NodeMessage)
}
