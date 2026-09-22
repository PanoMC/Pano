package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerConsoleBufferTest {
    private fun line(index: Int) = ConsoleLineData(t = index.toLong(), l = "INFO", m = "line $index")

    @Test
    fun `an empty buffer has no lines and nothing dropped`() {
        val buffer = ServerConsoleBuffer(4)

        assertEquals(0, buffer.size())
        assertEquals(0L, buffer.getDroppedCount())
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `lines come back in the order they arrived`() {
        val buffer = ServerConsoleBuffer(4)

        buffer.add(listOf(line(1), line(2)))
        buffer.add(listOf(line(3)))

        assertEquals(listOf("line 1", "line 2", "line 3"), buffer.snapshot().map { it.m })
    }

    @Test
    fun `the buffer never grows past its capacity and keeps the newest lines`() {
        val buffer = ServerConsoleBuffer(3)

        buffer.add((1..10).map { line(it) })

        assertEquals(3, buffer.size())
        assertEquals(listOf("line 8", "line 9", "line 10"), buffer.snapshot().map { it.m })
    }

    @Test
    fun `evicting old lines is not counted as dropping them`() {
        val buffer = ServerConsoleBuffer(2)

        buffer.add((1..5).map { line(it) })

        assertEquals(0L, buffer.getDroppedCount())
    }

    @Test
    fun `dropped counts reported by the server accumulate`() {
        val buffer = ServerConsoleBuffer(4)

        buffer.add(listOf(line(1)), reportedDropped = 3)
        buffer.add(listOf(line(2)), reportedDropped = 7)
        buffer.add(listOf(line(3)))

        assertEquals(10L, buffer.getDroppedCount())
    }

    @Test
    fun `a negative or zero limit returns nothing`() {
        val buffer = ServerConsoleBuffer(4)

        buffer.add(listOf(line(1), line(2)))

        assertTrue(buffer.snapshot(0).isEmpty())
        assertTrue(buffer.snapshot(-5).isEmpty())
    }

    @Test
    fun `a snapshot limit returns the newest lines only`() {
        val buffer = ServerConsoleBuffer(10)

        buffer.add((1..6).map { line(it) })

        assertEquals(listOf("line 5", "line 6"), buffer.snapshot(2).map { it.m })
        assertEquals(6, buffer.snapshot(100).size)
    }

    @Test
    fun `clearing drops the lines and the dropped counter`() {
        val buffer = ServerConsoleBuffer(4)

        buffer.add(listOf(line(1)), reportedDropped = 2)
        buffer.clear()

        assertEquals(0, buffer.size())
        assertEquals(0L, buffer.getDroppedCount())
    }

    @Test
    fun `concurrent writers never lose a line or corrupt the buffer`() {
        val buffer = ServerConsoleBuffer(1000)
        val threads = (1..8).map { thread ->
            Thread {
                repeat(100) { i ->
                    buffer.add(listOf(line(thread * 1000 + i)))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(800, buffer.size())
        assertEquals(800, buffer.snapshot().size)
    }

    @Test
    fun `the shipped capacity matches the console contract`() {
        assertEquals(2000, ServerConsoleBuffer.DEFAULT_CAPACITY)
    }
}
