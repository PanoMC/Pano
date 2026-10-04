package com.panomc.platform.server.channel

import com.panomc.platform.error.ServerOffline
import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.NodeRequestMessage
import com.panomc.platform.server.RawPayloadCarrier
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.message.RelayServerMessage
import com.panomc.platform.server.message.RelayServerRequestMessage
import io.vertx.core.json.JsonObject

/**
 * The agent-lite channel: the Pano plugin inside the running server (SM-47, §2.4.17 C).
 *
 * The node's message is re-framed rather than re-modelled. Its event name and its whole payload go
 * out on the plugin socket exactly as they would have gone out on the node one, which is what
 * makes "the node's request shapes verbatim" a fact about the code rather than a promise in a
 * document: there is only one definition of `FILE_LIST`, and both sides read it.
 *
 * The answer comes back as `FILE_RESULT` carrying the same `eventId`, the same name the node
 * protocol uses for every reply, and is handed back as raw JSON so the caller cannot tell the two
 * channels apart.
 */
class PluginSideChannel(
    private val serverId: Long,
    override val serverUuid: String,
    private val serverManager: ServerManager
) : ServerSideChannel {
    override val source = ServerFeatureSource.PLUGIN

    /** A plugin channel has no node behind it, which is the whole point of having one. */
    override val nodeId: Long? = null

    override suspend fun request(message: NodeRequestMessage, timeoutMs: Long): JsonObject {
        val reply = serverManager.request(
            serverId,
            RelayServerRequestMessage(message.getResponseName(), payloadOf(message)),
            timeoutMs
        )

        // A plugin that answered something other than a FILE_RESULT answered a question nobody
        // asked; treated as a failure rather than as an empty success.
        return (reply as? RawPayloadCarrier)?.raw ?: JsonObject().put("ok", false)
    }

    override fun send(message: NodeMessage) {
        val sent = serverManager.sendMessage(
            serverId,
            RelayServerMessage(message.getResponseName(), payloadOf(message))
        )

        if (!sent) {
            throw ServerOffline()
        }
    }

    /**
     * The message's own fields, minus the two a plugin socket does not carry.
     *
     * `eventId` is assigned by whichever manager sends the frame, so a null left in the payload
     * would overwrite the one the relay is about to put there. `serverUuid` goes because this
     * socket *is* the server: a node has many and has to be told which, a plugin has exactly one
     * and being told would only give it something to disagree with.
     */
    companion object {
        fun payloadOf(message: NodeMessage): JsonObject = JsonObject.mapFrom(message).apply {
            remove("eventId")
            remove("serverUuid")
        }
    }
}
