package com.panomc.node

import com.panomc.node.console.ConsoleFileTailer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Reading `console.out` by offset: exactly once, across daemons and rotations (SM-62). */
class ConsoleFileTailerTest {
    @TempDir
    lateinit var directory: File

    private val file get() = File(directory, "console.out")
    private val rotated get() = File(directory, "console.out.1")

    private val lines = mutableListOf<String>()

    private fun tailer(offset: Long = 0, rotateAt: Long = ConsoleFileTailer.ROTATE_AT_BYTES) =
        ConsoleFileTailer(file, rotated, offset, rotateAt) { lines.addAll(it) }

    private fun append(text: String) = file.appendBytes(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `delivers complete lines in order and nothing twice`() {
        val tailer = tailer()

        assertEquals(0, tailer.poll())

        append("one\ntwo\n")
        assertEquals(2, tailer.poll())

        append("three\n")
        tailer.poll()
        tailer.poll()

        assertEquals(listOf("one", "two", "three"), lines)
        assertEquals(file.length(), tailer.committedOffset)
    }

    @Test
    fun `holds back a line still being written, and the committed offset stops before it`() {
        val tailer = tailer()

        append("done\nhal")
        tailer.poll()

        assertEquals(listOf("done"), lines)
        assertEquals(5L, tailer.committedOffset)

        append("f\n")
        tailer.poll()

        assertEquals(listOf("done", "half"), lines)
        assertEquals(file.length(), tailer.committedOffset)
    }

    @Test
    fun `a second reader picks up exactly where the first let go`() {
        val first = tailer()

        append("a\nb\nhalf-")
        first.poll()
        first.stop()

        // Written while no daemon was reading.
        append("line\nc\n")

        val handedOver = first.committedOffset
        val carried = mutableListOf<String>()

        ConsoleFileTailer(file, rotated, handedOver) { carried.addAll(it) }.poll()

        assertEquals(listOf("a", "b"), lines)
        assertEquals(listOf("half-line", "c"), carried)
    }

    @Test
    fun `a character split across reads survives`() {
        val tailer = tailer()
        val bytes = "héllo ✓\n".toByteArray(Charsets.UTF_8)

        file.appendBytes(bytes.copyOfRange(0, 2))
        tailer.poll()
        file.appendBytes(bytes.copyOfRange(2, bytes.size))
        tailer.poll()

        assertEquals(listOf("héllo ✓"), lines)
    }

    @Test
    fun `strips carriage returns`() {
        val tailer = tailer()

        append("windows\r\n")
        tailer.poll()

        assertEquals(listOf("windows"), lines)
    }

    @Test
    fun `a file truncated underneath it is read again from the start`() {
        val tailer = tailer()

        append("old one\nold two\n")
        tailer.poll()

        file.writeText("new\n")
        tailer.poll()

        assertEquals(listOf("old one", "old two", "new"), lines)
    }

    @Test
    fun `rotates with copytruncate once everything is read`() {
        val tailer = tailer(rotateAt = 10)

        append("0123456789\nnext")
        tailer.poll()

        // A partial line pending: no rotation, or its first half would be lost.
        assertFalse(rotated.exists())

        append("\n")
        tailer.poll()

        assertTrue(rotated.exists())
        assertEquals("0123456789\nnext\n", rotated.readText())
        assertEquals(0L, file.length())
        assertEquals(0L, tailer.committedOffset)

        // The writer appends; the reader carries on from the new start.
        append("after\n")
        tailer.poll()

        assertEquals(listOf("0123456789", "next", "after"), lines)
    }

    @Test
    fun `drain delivers the unfinished last line`() {
        val tailer = tailer()

        append("full\nno newline")
        assertEquals(2, tailer.drain())

        assertEquals(listOf("full", "no newline"), lines)
    }

    @Test
    fun `reads a large backlog in chunks`() {
        val count = 50_000
        val text = buildString { repeat(count) { append("line number $it with some padding\n") } }

        append(text)

        val tailer = tailer()
        assertEquals(count, tailer.poll())
        assertEquals("line number ${count - 1} with some padding", lines.last())
    }

    @Test
    fun `replay reads from an offset and keeps only the newest lines`() {
        append("seen\n")
        val offset = file.length()
        append("a\nb\nc\nlast without newline")

        assertEquals(listOf("a", "b", "c", "last without newline"), ConsoleFileTailer.readLines(file, offset))
        assertEquals(listOf("c", "last without newline"), ConsoleFileTailer.readLines(file, offset, maxLines = 2))
        assertEquals(emptyList<String>(), ConsoleFileTailer.readLines(File(directory, "missing"), 0))
    }
}
