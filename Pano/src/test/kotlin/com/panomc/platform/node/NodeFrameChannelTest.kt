package com.panomc.platform.node

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pano's side of node frame sessions (node protocol 9). The node side, against real sockets, is
 * `FrameSessionConnectionTest` in the Node module; the frames built by hand here are exactly what
 * that node sends, field for field.
 */
class NodeFrameChannelTest {
    private val clientNonce = NodeFrameSession.newNonce()

    /** A frame the way a new node seals it: its own direction, the session id, the next number. */
    private fun fromNode(sessionId: String, sequence: Long, event: String = "NODE_HELLO") = JsonObject()
        .put("event", event)
        .put("_sid", sessionId)
        .put("_dir", "node")
        .put("_seq", sequence)

    @Test
    fun `a new node gets a session, sealed frames and no replays`() {
        val channel = NodeFrameChannel.open(clientNonce)
        val answer = channel.answer!!

        assertTrue(channel.isSealed)
        assertEquals("FRAME_SESSION", answer.getString("event"))
        assertEquals(clientNonce, answer.getString("client"))
        assertTrue(NodeFrameSession.isNonce(answer.getString("server")))

        val sessionId = "$clientNonce.${answer.getString("server")}"

        val first = JsonObject(channel.outbound(JsonObject().put("event", "DELETE_SERVER").encode()))
        val second = JsonObject(channel.outbound(JsonObject().put("event", "POWER").encode()))

        assertEquals(sessionId, first.getString("_sid"))
        assertEquals("pano", first.getString("_dir"))
        assertEquals(1L, first.getLong("_seq"))
        assertEquals(2L, second.getLong("_seq"))

        assertTrue(channel.accepts(fromNode(sessionId, 1)))
        assertTrue(channel.accepts(fromNode(sessionId, 3)))
        // A little out of order is fine (two threads writing), the same number twice never is.
        assertTrue(channel.accepts(fromNode(sessionId, 2)))
        assertFalse(channel.accepts(fromNode(sessionId, 2)), "a replayed frame")
        assertFalse(channel.accepts(fromNode(sessionId, 3)), "a replayed frame")

        // Pano's own frame bounced back, another connection's frame, and an unsealed one.
        assertFalse(channel.accepts(first))
        assertFalse(channel.accepts(fromNode("$clientNonce.other", 4)))
        assertFalse(channel.accepts(JsonObject().put("event", "NODE_HELLO")))
    }

    @Test
    fun `a frame recorded on one connection is refused on the next`() {
        val old = NodeFrameChannel.open(clientNonce)
        val oldSession = "$clientNonce.${old.answer!!.getString("server")}"

        assertTrue(old.accepts(fromNode(oldSession, 1)))

        // The attacker reuses the node's nonce; Pano's own nonce still makes it a new session.
        val next = NodeFrameChannel.open(clientNonce)

        assertFalse(next.accepts(fromNode(oldSession, 1)))
    }

    @Test
    fun `an old node keeps the old frames`() {
        val channel = NodeFrameChannel.open(null)
        val message = JsonObject().put("event", "POWER").put("action", "STOP").encode()

        assertFalse(channel.isSealed)
        assertNull(channel.answer)
        assertEquals(message, channel.outbound(message))
        assertTrue(channel.accepts(JsonObject().put("event", "NODE_HELLO")))
    }

    @Test
    fun `a node that already spoke frame sessions cannot connect without one`() {
        assertNull(NodeFrameChannel.refusal(null, 8), "an old node is let in the old way")
        assertNull(NodeFrameChannel.refusal(clientNonce, 8), "a node updated to 9 opens its first session")
        assertNull(NodeFrameChannel.refusal(clientNonce, NodeProtocol.FRAME_SESSION_VERSION))
        assertEquals(NodeFrameChannel.Refusal.SESSION_REQUIRED, NodeFrameChannel.refusal(null, NodeProtocol.FRAME_SESSION_VERSION))
        assertEquals(NodeFrameChannel.Refusal.BAD_NONCE, NodeFrameChannel.refusal("not a nonce!", 9))
        assertEquals(NodeFrameChannel.Refusal.BAD_NONCE, NodeFrameChannel.refusal("short", 9))
    }
}
