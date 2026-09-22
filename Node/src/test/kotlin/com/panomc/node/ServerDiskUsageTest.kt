package com.panomc.node

import com.panomc.node.host.ServerDiskUsage
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

class ServerDiskUsageTest {
    @TempDir
    lateinit var root: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    /** The measurements this test lets through, so a walk can be held mid-flight on purpose. */
    private val pending = ArrayDeque<() -> Unit>()

    private var now: Long = 1_000_000L

    @Test
    fun `adds up every regular file under the directory`() {
        val server = File(root, "server").apply { mkdirs() }

        File(server, "server.jar").writeBytes(ByteArray(1024))
        File(server, "world/region").apply { mkdirs() }
        File(server, "world/region/r.0.0.mca").writeBytes(ByteArray(4096))
        File(server, "plugins").mkdirs()

        assertEquals(1024L + 4096L, ServerDiskUsage.directorySize(server))
    }

    @Test
    fun `an empty directory is zero rather than a failure`() {
        val server = File(root, "empty").apply { mkdirs() }

        assertEquals(0L, ServerDiskUsage.directorySize(server))
    }

    @Test
    fun `a directory that does not exist measures nothing`() {
        assertEquals(0L, ServerDiskUsage.directorySize(File(root, "gone")))
    }

    @Test
    fun `a symbolic link is not followed, so what it points at is not billed twice`() {
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }

        File(elsewhere, "big.mca").writeBytes(ByteArray(8192))

        val server = File(root, "server").apply { mkdirs() }

        File(server, "own.jar").writeBytes(ByteArray(512))

        val linked = try {
            Files.createSymbolicLink(File(server, "world").toPath(), elsewhere.toPath())

            Files.createSymbolicLink(File(server, "big.mca").toPath(), File(elsewhere, "big.mca").toPath())

            true
        } catch (_: Exception) {
            false
        }

        // Windows without developer mode cannot make one at all; there the rule is untestable
        // rather than broken.
        assumeTrue(linked, "this host does not allow creating symbolic links")

        assertEquals(512L, ServerDiskUsage.directorySize(server))
    }

    @Test
    fun `a path that cannot be read is skipped instead of losing the whole measurement`() {
        val server = File(root, "server").apply { mkdirs() }

        File(server, "server.jar").writeBytes(ByteArray(64))

        val secret = File(server, "secret").apply { mkdirs() }

        File(secret, "inside.dat").writeBytes(ByteArray(32))

        secret.setReadable(false, false)

        try {
            // Either 96 (running as root, which can read it anyway) or 64; what matters is that a
            // directory the daemon may not enter does not throw the walk away.
            assertTrue(ServerDiskUsage.directorySize(server) >= 64L)
        } finally {
            secret.setReadable(true, false)
        }
    }

    @Test
    fun `the first tick reports nothing and the one after it reports the measurement`() {
        val server = File(root, "server").apply { mkdirs() }

        File(server, "server.jar").writeBytes(ByteArray(2048))

        val usage = usage()

        assertNull(usage.bytesOf("a", server), "nothing has been walked yet")

        runPending()

        assertEquals(2048L, usage.bytesOf("a", server))
    }

    @Test
    fun `a measurement is kept for five minutes and re-walked after them`() {
        val server = File(root, "server").apply { mkdirs() }

        File(server, "server.jar").writeBytes(ByteArray(1000))

        val usage = usage()

        usage.bytesOf("a", server)

        runPending()

        File(server, "world.dat").writeBytes(ByteArray(500))

        now += ServerDiskUsage.CACHE_MILLIS - 1

        assertEquals(1000L, usage.bytesOf("a", server))
        assertTrue(pending.isEmpty(), "a fresh measurement must not be walked again")

        now += 1

        assertEquals(1000L, usage.bytesOf("a", server), "the stale figure is reported while the walk runs")

        runPending()

        assertEquals(1500L, usage.bytesOf("a", server))
    }

    @Test
    fun `a walk already running is never waited on and never started twice`() {
        val server = File(root, "server").apply { mkdirs() }

        File(server, "server.jar").writeBytes(ByteArray(777))

        val usage = usage()

        assertNull(usage.bytesOf("a", server))
        assertEquals(1, pending.size)

        // The tick ten seconds later, with the first walk still going.
        now += 10_000

        assertNull(usage.bytesOf("a", server))
        assertEquals(1, pending.size, "the second tick must not queue another walk")

        runPending()

        assertEquals(777L, usage.bytesOf("a", server))
    }

    @Test
    fun `invalidating drops the figure and the next tick measures again`() {
        val server = File(root, "server").apply { mkdirs() }

        File(server, "server.jar").writeBytes(ByteArray(100))

        val usage = usage()

        usage.bytesOf("a", server)

        runPending()

        assertEquals(100L, usage.bytesOf("a", server))

        // What a restore, an import, a reinstall or a delete leaves behind.
        File(server, "server.jar").writeBytes(ByteArray(300))

        usage.invalidate("a")

        assertNull(usage.bytesOf("a", server), "the figure the restore invalidated is not reported again")

        runPending()

        assertEquals(300L, usage.bytesOf("a", server))
    }

    @Test
    fun `two servers are measured and remembered apart`() {
        val one = File(root, "one").apply { mkdirs() }
        val two = File(root, "two").apply { mkdirs() }

        File(one, "a.jar").writeBytes(ByteArray(10))
        File(two, "b.jar").writeBytes(ByteArray(20))

        val usage = usage()

        usage.bytesOf("one", one)
        usage.bytesOf("two", two)

        runPending()

        assertEquals(10L, usage.bytesOf("one", one))
        assertEquals(20L, usage.bytesOf("two", two))

        usage.invalidate("one")

        assertNull(usage.bytesOf("one", one))
        assertEquals(20L, usage.bytesOf("two", two))
    }

    @Test
    fun `a walk that blows up leaves the server measurable again`() {
        val usage = ServerDiskUsage(logger, { _ -> throw IllegalStateException("no worker pool") }, { now })

        assertNull(usage.bytesOf("a", root))

        // The failed attempt must not leave the uuid marked as "already walking" forever.
        assertNull(usage.bytesOf("a", root))
    }

    @Test
    fun `the partition behind a server directory is read without a walk`() {
        val server = File(root, "server").apply { mkdirs() }

        val total = ServerDiskUsage.totalSpace(server)

        // Whatever this machine's temp directory is on, it has a size, and it is at least as big
        // as the directory that sits on it.
        assertNotNull(total)
        assertTrue(total!! > 0L)
    }

    @Test
    fun `a directory that is not there has no partition to report`() {
        // Zero is what the JDK answers for a path it cannot stat, and a disk of no size is not a
        // fact worth sending.
        assertNull(ServerDiskUsage.totalSpace(File(root, "gone")))
    }

    private fun usage() = ServerDiskUsage(logger, { work -> pending.addLast(work) }, { now })

    private fun runPending() {
        while (pending.isNotEmpty()) {
            pending.removeFirst()()
        }
    }
}
