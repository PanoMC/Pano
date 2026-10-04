package com.panomc.node

import com.panomc.node.console.ConsoleLogWriter
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executor

/**
 * The node's coloured copy of a console (§2.4.21 B): the record format, the rotation, and that the
 * console can never be made to wait for it.
 */
class ConsoleLogWriterTest {
    @TempDir
    lateinit var root: File

    private val log = ByteArrayOutputStream()

    private val logger = NodeLogger("test", PrintStream(log))

    /** Writes happen when this test says so, which is how "never blocks" is observable at all. */
    private val pending = ArrayDeque<Runnable>()

    private val deferred = Executor { pending.addLast(it) }

    private val inline = Executor { it.run() }

    private fun server() = File(root, "server").apply { mkdirs() }

    private fun runPending() {
        while (pending.isNotEmpty()) {
            pending.removeFirst().run()
        }
    }

    @Test
    fun `records are the timestamp, a tab and the line with its colour codes`() {
        val server = server()
        val writer = ConsoleLogWriter(server, logger, executor = inline)

        writer.append(1_000, "\u001b[31mred\u001b[0m plain")
        writer.append(2_000, "[Pano:admin] > say hi")

        assertEquals(
            "1000\t\u001b[31mred\u001b[0m plain\n2000\t[Pano:admin] > say hi\n",
            ConsoleLogWriter.file(server).readText()
        )
        assertEquals(".pano-node/console.log", ConsoleLogWriter.file(server).relativeTo(server).path)
    }

    @Test
    fun `appending only queues, and the writes land later in one batch`() {
        val server = server()
        val writer = ConsoleLogWriter(server, logger, executor = deferred)

        writer.append(1, "a")
        writer.append(2, "b")
        writer.append(3, "c")

        // Nothing on disk yet, and one drain scheduled for all three.
        assertFalse(ConsoleLogWriter.file(server).exists())
        assertEquals(1, pending.size)

        runPending()

        assertEquals(listOf("1\ta", "2\tb", "3\tc"), ConsoleLogWriter.file(server).readLines())
    }

    @Test
    fun `a newline inside a message cannot forge the next record`() {
        val server = server()

        ConsoleLogWriter(server, logger, executor = inline).append(5, "one\ntwo\rthree")

        assertEquals(listOf("5\tone two three"), ConsoleLogWriter.file(server).readLines())
    }

    @Test
    fun `rotates at the size limit and keeps two old files`() {
        val server = server()

        // Each record is "N\t" + 20 chars + "\n" = 23–24 bytes; 100 bytes holds four of them.
        val writer = ConsoleLogWriter(server, logger, maxFileBytes = 100, executor = inline)

        (1..20).forEach { index -> writer.append(index.toLong(), "x".repeat(20)) }

        val file = ConsoleLogWriter.file(server)
        val first = File(file.parentFile, "console.log.1")
        val second = File(file.parentFile, "console.log.2")

        assertTrue(file.length() <= 100)
        assertTrue(first.isFile && first.length() <= 100)
        assertTrue(second.isFile && second.length() <= 100)
        assertFalse(File(file.parentFile, "console.log.3").exists(), "never more than two rotations")

        // Newest in console.log, older in .1, older still in .2 — and nothing out of order.
        val stamps = (second.readLines() + first.readLines() + file.readLines()).map { it.substringBefore('\t').toInt() }

        assertEquals(stamps.sorted(), stamps)
        assertEquals(20, stamps.last())
        assertEquals(listOf(first, second), ConsoleLogWriter.rotations(server))
    }

    @Test
    fun `a single line bigger than the limit is still written`() {
        val server = server()

        ConsoleLogWriter(server, logger, maxFileBytes = 10, executor = inline).append(1, "y".repeat(50))

        assertEquals(listOf("1\t" + "y".repeat(50)), ConsoleLogWriter.file(server).readLines())
    }

    @Test
    fun `a deleted server is not brought back as a directory holding a log`() {
        val server = File(root, "gone")

        ConsoleLogWriter(server, logger, executor = inline).append(1, "late line")

        assertFalse(server.exists())
    }

    @Test
    fun `a failing disk is logged once and never throws into the console`() {
        val server = server()

        // A file where the .pano-node directory should be: every write fails.
        File(server, ".pano-node").writeText("not a directory")

        val writer = ConsoleLogWriter(server, logger, executor = inline)

        writer.append(1, "a")
        writer.append(2, "b")

        assertEquals(1, log.toString().lines().count { it.contains("could not write the console log", ignoreCase = true) })
    }

    @Test
    fun `lines beyond the queue are skipped from the file, not from the console`() {
        val server = server()
        val writer = ConsoleLogWriter(server, logger, executor = deferred)

        repeat(ConsoleLogWriter.MAX_QUEUED_LINES + 5) { writer.append(it.toLong(), "l") }

        runPending()

        assertEquals(ConsoleLogWriter.MAX_QUEUED_LINES, ConsoleLogWriter.file(server).readLines().size)
        assertEquals(1, log.toString().lines().count { it.contains("falling behind") })
    }
}
