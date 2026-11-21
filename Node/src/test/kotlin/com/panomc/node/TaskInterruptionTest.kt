package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.net.PlatformConnection
import com.panomc.node.task.InFlightTasks
import com.panomc.node.task.SelfUpdateService
import com.panomc.node.task.TaskReporter
import com.panomc.node.net.PlatformUrls
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

/**
 * A task the daemon's stop or restart cuts short is reported FAILED, and the restart that applies
 * an update waits for running tasks first (koyluler.tr, 1.5.0-alpha.2: a plugin download died with
 * the update's restart, logged `failed: null` and stayed RUNNING in Pano for ten minutes).
 */
class TaskInterruptionTest {
    private val vertx = Vertx.vertx()
    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    @TempDir
    lateinit var dataDir: File

    @AfterEach
    fun close() {
        vertx.close()
    }

    private fun reporter() = TaskReporter(PlatformConnection(vertx, logger, NodeConfig()))

    @Test
    fun `a running task is in flight until its terminal frame`() {
        val reporter = reporter()

        reporter.running("t1", "s1", "PLUGIN_INSTALL", 5, "Downloading a.jar")
        reporter.running("t2", "s1", "BACKUP", 5, "Packing")

        assertEquals(setOf("t1", "t2"), reporter.inFlight.all().map { it.taskId }.toSet())

        reporter.done("t2", "s1", "BACKUP", "Done")

        assertEquals(listOf("t1"), reporter.inFlight.all().map { it.taskId })

        // A frame after the end does not bring the task back.
        reporter.running("t2", "s1", "BACKUP", 50, "late")

        assertEquals(listOf("t1"), reporter.inFlight.all().map { it.taskId })
    }

    @Test
    fun `the way out fails every task still in flight, once`() {
        val reporter = reporter()

        reporter.running("t1", "s1", "PLUGIN_INSTALL", 5, "Downloading a.jar")
        reporter.running("t2", "s2", "BACKUP", 5, "Packing")

        assertEquals(2, reporter.failInFlight())
        assertEquals(0, reporter.inFlight.count())

        // The interrupted handler's own FAILED afterwards is swallowed; nothing is left to fail.
        reporter.failed("t1", "s1", "PLUGIN_INSTALL", "InterruptedException")

        assertEquals(0, reporter.failInFlight())
    }

    @Test
    fun `an interrupt is reported as interrupted, not as null`() {
        assertEquals(InFlightTasks.INTERRUPTED_ERROR, InFlightTasks.errorOf(InterruptedException()))
        assertEquals(InFlightTasks.INTERRUPTED_ERROR, InFlightTasks.errorOf(CancellationException()))
        assertEquals(InFlightTasks.INTERRUPTED_ERROR, InFlightTasks.errorOf(IOException(ClosedByInterruptException())))
        assertEquals("boom", InFlightTasks.errorOf(IllegalStateException("boom")))
        assertEquals("IllegalStateException", InFlightTasks.errorOf(IllegalStateException()))
    }

    @Test
    fun `the update's restart waits for other tasks, leaving out its own kind`() {
        val reporter = reporter()

        reporter.running("1.5.0", null, SelfUpdateService.KIND, 5, "Downloading")
        reporter.running("t1", "s1", "PLUGIN_INSTALL", 5, "Downloading a.jar")

        var now = 0L
        var sleeps = 0

        val service = SelfUpdateService(
            dataDir,
            reporter,
            PlatformUrls(NodeConfig()),
            logger,
            otherTasksInFlight = { reporter.inFlight.count(excludingKind = SelfUpdateService.KIND) },
            waitMaxMillis = 60_000L,
            pollMillis = 1_000L,
            clock = { now },
            sleep = { millis ->
                now += millis
                sleeps++

                // The plugin install ends after three polls.
                if (sleeps == 3) {
                    reporter.done("t1", "s1", "PLUGIN_INSTALL", "Installed a.jar")
                }
            }
        ) { }

        service.awaitOtherTasks("1.5.0", "1.5.0")

        assertEquals(3, sleeps)
    }

    @Test
    fun `the update's restart gives up waiting after its bound`() {
        val reporter = reporter()

        reporter.running("t1", "s1", "BACKUP", 5, "Packing")

        var now = 0L

        val service = SelfUpdateService(
            dataDir,
            reporter,
            PlatformUrls(NodeConfig()),
            logger,
            otherTasksInFlight = { reporter.inFlight.count(excludingKind = SelfUpdateService.KIND) },
            waitMaxMillis = 10_000L,
            pollMillis = 1_000L,
            clock = { now },
            sleep = { now += it }
        ) { }

        service.awaitOtherTasks("1.5.0", "1.5.0")

        assertTrue(now >= 10_000L)
        assertEquals(1, reporter.inFlight.count(excludingKind = SelfUpdateService.KIND))
    }
}
