package com.panomc.node

import com.panomc.node.console.ConsoleBuffer
import com.panomc.node.console.ConsoleLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleBufferTest {
    private var now = 0L

    private fun buffer() = ConsoleBuffer { now }

    private fun line(index: Int) = ConsoleLine(now, "INFO", "line $index")

    @Test
    fun `keeps scrollback but queues nothing until streaming is on`() {
        val buffer = buffer()

        repeat(10) { buffer.add(line(it)) }

        assertEquals(10, buffer.snapshot().size)
        assertFalse(buffer.hasPending())
        assertNull(buffer.takeBatch())
    }

    @Test
    fun `replays the scrollback oldest first when streaming starts`() {
        val buffer = buffer()

        repeat(3) { buffer.add(line(it)) }

        buffer.setStreaming(true)

        val batch = buffer.takeBatch()!!

        assertEquals(listOf("line 0", "line 1", "line 2"), batch.lines.map { it.m })
        assertEquals(0L, batch.dropped)
    }

    @Test
    fun `drops the oldest lines past the scrollback size`() {
        val buffer = buffer()

        repeat(ConsoleBuffer.RING_BUFFER_SIZE + 5) { buffer.add(line(it)) }

        val snapshot = buffer.snapshot()

        assertEquals(ConsoleBuffer.RING_BUFFER_SIZE, snapshot.size)
        assertEquals("line 5", snapshot.first().m)
    }

    @Test
    fun `never hands out more than one batch worth of lines at a time`() {
        val buffer = buffer()

        buffer.setStreaming(true)

        repeat(ConsoleBuffer.BATCH_SIZE + 20) { buffer.add(line(it)) }

        assertTrue(buffer.isBatchReady())
        assertEquals(ConsoleBuffer.BATCH_SIZE, buffer.takeBatch()!!.lines.size)
        assertEquals(20, buffer.takeBatch()!!.lines.size)
    }

    @Test
    fun `counts dropped lines and reports them with the next batch`() {
        val buffer = buffer()

        buffer.setStreaming(true)

        repeat(ConsoleBuffer.PENDING_LIMIT + 7) { buffer.add(line(it)) }

        var dropped = 0L
        var taken = 0

        while (taken < ConsoleBuffer.PENDING_LIMIT) {
            val batch = buffer.takeBatch() ?: break

            dropped += batch.dropped
            taken += batch.lines.size
        }

        assertEquals(7L, dropped)
    }

    @Test
    fun `stops at the per-second ceiling and resumes in the next window`() {
        val buffer = buffer()

        buffer.setStreaming(true)

        repeat(ConsoleBuffer.PENDING_LIMIT) { buffer.add(line(it)) }

        var sent = 0

        while (true) {
            val batch = buffer.takeBatch() ?: break

            sent += batch.lines.size
        }

        assertEquals(ConsoleBuffer.MAX_LINES_PER_SECOND, sent)

        now += 1_000

        assertEquals(ConsoleBuffer.BATCH_SIZE, buffer.takeBatch()!!.lines.size)
    }

    @Test
    fun `stops a batch early when the lines are huge`() {
        val buffer = buffer()

        buffer.setStreaming(true)

        val fat = "x".repeat(10 * 1024)

        repeat(20) { buffer.add(ConsoleLine(now, "INFO", fat)) }

        val batch = buffer.takeBatch()!!

        assertTrue(batch.lines.size < ConsoleBuffer.BATCH_SIZE)
        assertTrue(batch.lines.sumOf { it.m.length } <= ConsoleBuffer.MAX_BATCH_BYTES + fat.length)
    }

    @Test
    fun `turning streaming off clears the queue but keeps the scrollback`() {
        val buffer = buffer()

        buffer.setStreaming(true)

        repeat(5) { buffer.add(line(it)) }

        buffer.setStreaming(false)

        assertFalse(buffer.hasPending())
        assertEquals(5, buffer.snapshot().size)
    }

    @Test
    fun `the scrollback a crash is answered with survives nobody having watched it`() {
        val buffer = buffer()

        // Streaming never turned on: this is the server that crashed at four in the morning.
        repeat(600) { buffer.add(line(it)) }

        val tail = buffer.snapshot().takeLast(NodeDaemon.CRASH_LINES)

        assertEquals(ConsoleBuffer.RING_BUFFER_SIZE, buffer.snapshot().size)
        assertEquals(NodeDaemon.CRASH_LINES, tail.size)
        assertEquals("line 599", tail.last().m)
        assertFalse(buffer.hasPending())
    }

    @Test
    fun `reads the crash reason off the tail`() {
        val tail = listOf(
            ConsoleLine(1, "INFO", "Starting Velocity"),
            ConsoleLine(2, "ERROR", "java.lang.UnsupportedClassVersionError: Velocity was compiled by Java 25"),
            ConsoleLine(3, "ERROR", "Server process exited with code 1.")
        )

        assertEquals(
            "java.lang.UnsupportedClassVersionError: Velocity was compiled by Java 25",
            NodeDaemon.crashReason(tail)
        )

        // A line the process printed to stdout still counts; an ordinary shutdown does not.
        assertEquals(
            "Exception in thread \"main\" java.lang.NoClassDefFoundError",
            NodeDaemon.crashReason(listOf(ConsoleLine(1, "INFO", "Exception in thread \"main\" java.lang.NoClassDefFoundError")))
        )

        assertNull(NodeDaemon.crashReason(listOf(ConsoleLine(1, "INFO", "Done (12.3s)!"))))
        assertNull(NodeDaemon.crashReason(emptyList()))
    }

    @Test
    fun `a JVM that refused to start is explained by its own words, not by the exit summary`() {
        // HotSpot prints these to stdout, so they arrive as INFO and carry no colon after "Error".
        val tail = listOf(
            ConsoleLine(1, "INFO", "Error occurred during initialization of VM"),
            ConsoleLine(2, "INFO", "Initial heap size set to a larger value than the maximum heap size"),
            ConsoleLine(3, "ERROR", "Server process exited with code 1.")
        )

        assertEquals("Error occurred during initialization of VM", NodeDaemon.crashReason(tail))

        assertEquals(
            "Unrecognized VM option 'UseFoo'",
            NodeDaemon.crashReason(
                listOf(
                    ConsoleLine(1, "INFO", "Unrecognized VM option 'UseFoo'"),
                    ConsoleLine(2, "INFO", "Error: Could not create the Java Virtual Machine."),
                    ConsoleLine(3, "ERROR", "Server process exited with code 1.")
                )
            )
        )

        // Nothing but the summary: no reason, rather than the exit code said twice.
        assertNull(NodeDaemon.crashReason(listOf(ConsoleLine(1, "ERROR", "Server process exited with code 137."))))
    }
}
