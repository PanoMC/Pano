package com.panomc.platform.node

import io.vertx.core.json.JsonObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Binds every frame of one node socket to that socket, its direction and its place in the stream
 * (node protocol 9). The same wire format as the node's own `FrameSession`; keep the two in step.
 *
 * The AES key is the node's for its whole life, so without this a frame recorded once -- a
 * `DELETE_SERVER`, a `BACKUP_RESTORE` -- would be accepted again on any later connection. Each side
 * brings a random nonce: the node sends its own in the [HEADER] of the upgrade, Pano answers with a
 * [EVENT] frame (the first thing it sends) holding both. From then on every frame carries the
 * session id ([ID_FIELD], both nonces), its direction ([DIRECTION_FIELD]) and a counter
 * ([SEQUENCE_FIELD]) inside the encrypted body, and the receiver drops anything that names another
 * session, the other direction, or a number it has already seen.
 *
 * Negotiated, so either side may be older: a Pano that does not know the header never answers it,
 * and the node then speaks the old way; a node that sends no header gets the old way from Pano.
 * Once both sides have spoken it, each refuses to fall back (Pano by the protocol the node
 * announced, the node by a flag in its config), so it cannot be stripped off later.
 */
class NodeFrameSession(
    val id: String,
    private val sendDirection: String,
    private val receiveDirection: String
) {
    private val sent = AtomicLong()

    private var highest = 0L

    /** Numbers seen within [WINDOW] of [highest]: frames may arrive a little out of order, never twice. */
    private val seen = java.util.TreeSet<Long>()

    /** [frame] with this session's id, the sending direction and the next number. */
    fun seal(frame: JsonObject): JsonObject = frame
        .put(ID_FIELD, id)
        .put(DIRECTION_FIELD, sendDirection)
        .put(SEQUENCE_FIELD, sent.incrementAndGet())

    /** Whether [frame] belongs here and was not seen before; one that does is remembered. */
    @Synchronized
    fun accept(frame: JsonObject): Boolean {
        val sequence = (frame.getValue(SEQUENCE_FIELD) as? Number)?.toLong() ?: return false

        if (frame.getValue(ID_FIELD) != id || frame.getValue(DIRECTION_FIELD) != receiveDirection || sequence <= 0) {
            return false
        }

        // Older than the window, or already seen: a replay.
        if (sequence <= highest - WINDOW || !seen.add(sequence)) {
            return false
        }

        if (sequence > highest) {
            highest = sequence

            seen.headSet(highest - WINDOW, true).clear()
        }

        return true
    }

    companion object {
        /** Upgrade request header with the node's nonce. */
        const val HEADER = "X-Pano-Frame-Session"

        /** Pano's answer, `{ event, client, server }`, the one frame that is not sealed. */
        const val EVENT = "FRAME_SESSION"

        const val ID_FIELD = "_sid"
        const val DIRECTION_FIELD = "_dir"
        const val SEQUENCE_FIELD = "_seq"

        /** How far behind the newest number a frame may still arrive. */
        const val WINDOW = 4096L

        const val FROM_NODE = "node"
        const val FROM_PANO = "pano"

        private val random = SecureRandom()

        fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        /** A nonce as [newNonce] makes them: what Pano accepts in the header. */
        fun isNonce(value: String?) = value != null && value.length in 16..64 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        fun sessionId(client: String, server: String) = "$client.$server"

        /** Pano's side of the session it opens with [client] (the node's nonce) and [server] (its own). */
        fun forPano(client: String, server: String) = NodeFrameSession(sessionId(client, server), FROM_PANO, FROM_NODE)

        /** The one unsealed frame: Pano's answer that opens the session. */
        fun answer(client: String, server: String): JsonObject =
            JsonObject().put("event", EVENT).put("client", client).put("server", server)
    }
}
