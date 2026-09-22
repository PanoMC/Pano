package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.server.AdoptedProcess
import com.panomc.node.server.ProcessRecord
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerRuntime
import com.panomc.node.server.ServerSpec
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executors

/**
 * What the registry does with ownership records at both ends of a daemon's life (SM-51).
 *
 * No process is ever started here. Adoption is driven through a runtime that answers the one
 * question the real ones answer -- "is the thing this record names still there" -- which is
 * exactly the seam the registry is written against.
 */
class ServerRegistryAdoptionTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val states = mutableListOf<Pair<String, ServerProcessState>>()

    private val listener = object : ServerProcessListener {
        override fun onState(
            server: ServerProcess,
            state: ServerProcessState,
            exitCode: Int?,
            pid: Long?,
            since: Long
        ) {
            states.add(server.uuid to state)
        }

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private fun registry(runtime: ServerRuntime) =
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), runtime, scheduler, logger, listener)

    private fun serverDirectory(uuid: String): File {
        val directory = File(File(dataDir, "servers"), uuid).apply { mkdirs() }

        File(directory, "server.json").writeText(
            """{"uuid":"$uuid","name":"$uuid","software":"paper","jar":"server.jar","memoryMb":1024,"port":25565}"""
        )

        return directory
    }

    private fun record(pid: Long = 4242) = ProcessRecord(
        pid = pid,
        startedAt = System.currentTimeMillis(),
        command = "server.jar",
        runtime = ProcessRuntime.ID
    )

    @Test
    fun `adopts a server whose recorded process is still there`() {
        val directory = serverDirectory("alive")

        ProcessRecordStore.write(directory, record())

        val registry = registry(FakeRuntime(alive = true))

        registry.load()

        val server = registry.get("alive")

        assertNotNull(server)
        assertEquals(ServerProcessState.RUNNING, server!!.state)
        assertTrue(server.adopted)
        assertFalse(server.stdinAvailable)
        assertEquals(listOf("alive" to ServerProcessState.RUNNING), states)

        // The record is what the *next* restart reads; adopting it must not consume it.
        assertNotNull(ProcessRecordStore.read(directory))

        assertEquals(listOf(server), registry.adopted())
    }

    @Test
    fun `deletes a stale record and leaves the server stopped`() {
        val directory = serverDirectory("stale")

        ProcessRecordStore.write(directory, record())

        val registry = registry(FakeRuntime(alive = false))

        registry.load()

        assertEquals(ServerProcessState.STOPPED, registry.get("stale")?.state)
        assertFalse(registry.get("stale")!!.adopted)
        assertTrue(registry.get("stale")!!.stdinAvailable)
        assertTrue(states.isEmpty())
        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `leaves a server with no record alone`() {
        serverDirectory("never-started")

        val registry = registry(FakeRuntime(alive = true))

        registry.load()

        assertEquals(ServerProcessState.STOPPED, registry.get("never-started")?.state)
        assertFalse(registry.get("never-started")!!.adopted)
    }

    @Test
    fun `refuses to adopt through a runtime the record was not written by`() {
        val directory = serverDirectory("docker-record")

        ProcessRecordStore.write(directory, record().copy(runtime = "DOCKER", container = "pano-docker-record"))

        // The real ProcessRuntime, which is what a node restarted without --runtime DOCKER has.
        val registry = registry(ProcessRuntime())

        registry.load()

        assertEquals(ServerProcessState.STOPPED, registry.get("docker-record")?.state)
        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `stopping an adopted server ends it, drops its record and gives the next start its pipes back`() {
        val directory = serverDirectory("stoppable")

        ProcessRecordStore.write(directory, record(pid = 77))

        val runtime = FakeRuntime(alive = true)
        val registry = registry(runtime)

        registry.load()

        val server = registry.get("stoppable")!!

        // Nothing can be written to it while it is adopted, and the node says so rather than
        // echoing a command the server never received.
        assertFalse(server.sendCommand("say hello", "tester"))

        server.stop("tester")

        waitUntil { server.state == ServerProcessState.STOPPED }

        assertEquals(1, runtime.stopped)
        assertFalse(server.adopted)
        assertTrue(server.stdinAvailable)

        // The exit this daemon did watch: now the record is meaningless and is removed.
        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `leaves the records and the processes alone when the daemon exits`() {
        val first = serverDirectory("one")
        val second = serverDirectory("two")

        ProcessRecordStore.write(first, record(pid = 11))
        ProcessRecordStore.write(second, record(pid = 22))

        val runtime = FakeRuntime(alive = true)
        val registry = registry(runtime)

        registry.load()

        states.clear()

        registry.shutdown()

        assertNotNull(ProcessRecordStore.read(first))
        assertNotNull(ProcessRecordStore.read(second))

        // Nothing was signalled and nothing changed state: the servers are still running, this
        // daemon simply stopped watching them.
        assertEquals(0, runtime.stopped)
        assertTrue(states.isEmpty())
        assertEquals(ServerProcessState.RUNNING, registry.get("one")?.state)
    }

    @Test
    fun `still stops everything when the operator asked it to`() {
        val directory = serverDirectory("one")

        ProcessRecordStore.write(directory, record(pid = 11))

        val runtime = FakeRuntime(alive = true)
        val registry = registry(runtime)

        registry.load()

        registry.shutdown(stopServers = true)

        assertEquals(1, runtime.stopped)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000

        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(10)
        }

        assertTrue(condition(), "the supervisor never got there")
    }

    /** A runtime that answers the adoption question without a process existing anywhere. */
    private class FakeRuntime(private val alive: Boolean) : ServerRuntime {
        var stopped = 0

        override val id = ProcessRuntime.ID

        override fun verify() {}

        override fun launch(request: ServerRuntime.LaunchRequest): Process =
            throw UnsupportedOperationException("no process is ever started in a test")

        override fun terminate(uuid: String, process: Process, timeoutSeconds: Long) = true

        override fun kill(uuid: String, process: Process) {}

        override fun remove(uuid: String) {}

        override fun adopt(uuid: String, directory: File, record: ProcessRecord): AdoptedProcess? =
            if (alive) FakeAdopted(record.pid) { stopped++ } else null

        override fun pluginEndpoint(nodeEndpoint: com.panomc.node.net.PlatformEndpoint?) = nodeEndpoint

        override fun sample(uuid: String, process: Process, metrics: ServerRuntime.ProcessSampler) =
            ServerRuntime.Sample(null, null)
    }

    private class FakeAdopted(override val pid: Long?, private val onStop: () -> Unit) : AdoptedProcess {
        @Volatile
        private var alive = true

        override fun isAlive() = alive

        override fun terminate(timeoutSeconds: Long): Boolean {
            onStop()

            alive = false

            return true
        }

        override fun kill() {
            onStop()

            alive = false
        }

        override fun sample(metrics: ServerRuntime.ProcessSampler) = ServerRuntime.Sample(null, null)

        override fun onExit(action: () -> Unit) {}
    }
}
