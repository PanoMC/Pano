package com.panomc.platform.node

import io.vertx.core.json.JsonObject

/**
 * Pano's half of one node socket's frames (node protocol 9): sealed and checked when the node asked
 * for a [NodeFrameSession], passed through unchanged for an older node that did not.
 */
class NodeFrameChannel private constructor(
    private val session: NodeFrameSession?,
    /** The unsealed frame that opens the session, sent before anything else; null for an old node. */
    val answer: JsonObject?
) {
    val isSealed: Boolean get() = session != null

    /** The text to encrypt and send for [message]. */
    fun outbound(message: String): String = session?.seal(JsonObject(message))?.encode() ?: message

    /** Whether an inbound [body] may be handled; false is a replay or a frame of another socket. */
    fun accepts(body: JsonObject): Boolean = session?.accept(body) ?: true

    companion object {
        /** Opens the channel of a socket whose upgrade carried [clientNonce], or none. */
        fun open(clientNonce: String?): NodeFrameChannel {
            if (clientNonce == null) {
                return NodeFrameChannel(null, null)
            }

            val serverNonce = NodeFrameSession.newNonce()

            return NodeFrameChannel(
                NodeFrameSession.forPano(clientNonce, serverNonce),
                NodeFrameSession.answer(clientNonce, serverNonce)
            )
        }

        /**
         * Why an upgrade with [clientNonce] from a node that last announced [protocolVersion] is
         * refused, or null when it may connect. A node that speaks frame sessions always sends its
         * nonce, so one without it is either an old jar put back or somebody else with its token.
         */
        fun refusal(clientNonce: String?, protocolVersion: Int): Refusal? = when {
            clientNonce != null && !NodeFrameSession.isNonce(clientNonce) -> Refusal.BAD_NONCE
            clientNonce == null && protocolVersion >= NodeProtocol.FRAME_SESSION_VERSION -> Refusal.SESSION_REQUIRED
            else -> null
        }
    }

    enum class Refusal { BAD_NONCE, SESSION_REQUIRED }
}
