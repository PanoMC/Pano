package com.panomc.node

import com.panomc.node.net.ServerMetricsFrames
import com.panomc.node.server.ServerRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a `SERVER_PROCESS_METRICS` frame carries, which is how Pano tells a running server from a
 * stopped one (§2.4.18 A).
 *
 * The keys are the contract. A stopped server's frame that carried a `cpu` of null would be read
 * as a process measured at nothing, and the server would be recorded as running with a flat line
 * through its whole history.
 */
class ServerMetricsFrameTest {
    @Test
    fun `a running server sends both names for its two numbers, plus the directory size`() {
        val frame = ServerMetricsFrames.process(
            "abc",
            1234,
            ServerRuntime.Sample(12.5, 4096),
            41231953920,
            500_107_862_016
        )

        assertEquals("abc", frame.getString("serverUuid"))
        assertEquals(1234L, frame.getLong("t"))
        assertEquals(12.5, frame.getDouble("cpu"))
        assertEquals(12.5, frame.getDouble("cpuPercent"))
        assertEquals(4096L, frame.getLong("memRss"))
        assertEquals(4096L, frame.getLong("rssBytes"))
        assertEquals(41231953920L, frame.getLong("diskBytes"))
        assertEquals(500_107_862_016L, frame.getLong("diskTotalBytes"))
    }

    @Test
    fun `a running server carries its own traffic when its runtime can count it`() {
        val frame = ServerMetricsFrames.process("abc", 1, ServerRuntime.Sample(1.0, 2), null, null, 5_000, 250)

        assertEquals(5_000L, frame.getLong("netRxBps"))
        assertEquals(250L, frame.getLong("netTxBps"))

        // A plain process cannot: the keys are there, the values are null, and Pano falls back.
        val plain = ServerMetricsFrames.process("abc", 1, ServerRuntime.Sample(1.0, 2), null, null)

        assertTrue(plain.containsKey("netRxBps"))
        assertNull(plain.getLong("netRxBps"))
    }

    @Test
    fun `a running server whose directory has not been walked yet still reports its process`() {
        val frame = ServerMetricsFrames.process("abc", 1, ServerRuntime.Sample(1.0, 2), null, 500_107_862_016)

        assertNull(frame.getLong("diskBytes"))
        // The partition is one system call, so it is there even when the walk is not.
        assertEquals(500_107_862_016L, frame.getLong("diskTotalBytes"))
        assertEquals(1.0, frame.getDouble("cpu"))
    }

    @Test
    fun `a stopped server sends its directory size and nothing that looks like a process`() {
        val frame = ServerMetricsFrames.diskOnly("abc", 99, 41231953920, 500_107_862_016)

        assertEquals("abc", frame.getString("serverUuid"))
        assertEquals(99L, frame.getLong("t"))
        assertEquals(41231953920L, frame.getLong("diskBytes"))
        assertEquals(500_107_862_016L, frame.getLong("diskTotalBytes"))

        assertFalse(frame.containsKey("cpu"), frame.encode())
        assertFalse(frame.containsKey("cpuPercent"), frame.encode())
        assertFalse(frame.containsKey("memRss"), frame.encode())
        assertFalse(frame.containsKey("rssBytes"), frame.encode())
        assertFalse(frame.containsKey("playerCount"), frame.encode())
        assertFalse(frame.containsKey("playerSample"), frame.encode())
        assertFalse(frame.containsKey("motd"), frame.encode())
        assertFalse(frame.containsKey("netRxBps"), "a stopped server has no traffic to report")
        assertFalse(frame.containsKey("netTxBps"), frame.encode())

        assertEquals(4, frame.size(), frame.encode())
    }

    @Test
    fun `the two frames are told apart by their keys alone`() {
        val stopped = ServerMetricsFrames.diskOnly("abc", 1, 10, 100)
        val running = ServerMetricsFrames.process("abc", 1, ServerRuntime.Sample(null, null), 10, 100)

        // Even a running server that could measure neither of its numbers keeps the keys, which is
        // what makes "this frame has no process half at all" a usable signal on the other side.
        assertTrue(running.containsKey("cpu") && running.containsKey("rssBytes"))
        assertFalse(stopped.containsKey("cpu") || stopped.containsKey("rssBytes"))
    }
}
