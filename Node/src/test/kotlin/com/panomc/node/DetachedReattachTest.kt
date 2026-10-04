package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.server.AdoptedProcess
import com.panomc.node.server.DetachedFiles
import com.panomc.node.server.DockerCommands
import com.panomc.node.server.DockerRuntime
import com.panomc.node.server.ProcessRecord
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.Reattachment
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerRuntime
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.Executors

/**
 * What a daemon does on start with a record written for re-attach (SM-62, §2.4.27), without a
 * real server: the runtimes' answers from what they find, and the registry booking an exit that
 * happened while no daemon was running.
 */
class DetachedReattachTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val states = mutableListOf<Pair<ServerProcessState, Int?>>()

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {
            states.add(state to exitCode)
        }

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private fun serverDirectory(uuid: String): File {
        val directory = File(File(dataDir, "servers"), uuid).apply { mkdirs() }

        File(directory, "server.json").writeText(
            """{"uuid":"$uuid","name":"$uuid","software":"paper","jar":"server.jar","memoryMb":1024,"port":25565,"crashRestart":false}"""
        )

        return directory
    }

    private fun detachedRecord(pid: Long = DEAD_PID, offset: Long = 0, runtime: String = ProcessRuntime.ID) = ProcessRecord(
        pid = pid,
        startedAt = System.currentTimeMillis(),
        command = "server.jar",
        runtime = runtime,
        io = ProcessRecord.IO_DETACHED,
        launcherPid = null,
        javaPid = pid,
        outOffset = offset
    )

    // ---- ProcessRuntime ---------------------------------------------------------------------

    @Test
    fun `a legacy record is left to legacy adoption`() {
        val directory = serverDirectory("legacy")

        val legacy = detachedRecord().copy(io = null)

        assertNull(ProcessRuntime().reattach("legacy", directory, legacy))
        assertNull(ProcessRuntime().reattach("legacy", directory, detachedRecord(runtime = DockerRuntime.ID)))
    }

    @Test
    fun `a server gone while away comes back as its exit code and what it said after the offset`() {
        val directory = serverDirectory("gone")
        val files = DetachedFiles(directory).apply { this.directory.mkdirs() }

        files.output.writeText("already read\n")
        val offset = files.output.length()
        files.output.appendText("Stopping server\nbye\n")
        files.exit.writeText("0\n")

        val found = ProcessRuntime().reattach("gone", directory, detachedRecord(offset = offset))

        assertInstanceOf(Reattachment.Exited::class.java, found)
        found as Reattachment.Exited

        assertEquals(0, found.exitCode)
        assertEquals(listOf("Stopping server", "bye"), found.replay)
    }

    @Test
    fun `a launcher killed before it could write the code leaves the code unknown`() {
        val directory = serverDirectory("killed")

        val found = ProcessRuntime().reattach("killed", directory, detachedRecord()) as Reattachment.Exited

        assertNull(found.exitCode)
        assertTrue(found.replay.isEmpty())
    }

    // ---- DockerRuntime ----------------------------------------------------------------------

    @Test
    fun `a running container gets a fresh attach and a log follow from the recorded instant`() {
        val started = mutableListOf<List<String>>()
        val docker = DockerRuntime(logger, { args, _ -> started.add(args); FakeProcess() }) { args ->
            DockerRuntime.CommandResult(true, if (args.contains("{{.State.Running}}")) "true\n" else "")
        }

        val record = detachedRecord(runtime = DockerRuntime.ID).copy(logsSince = 1_700_000_000_000_000_000L)
        val found = docker.reattach("abc", serverDirectory("abc"), record)

        assertInstanceOf(Reattachment.Container::class.java, found)
        assertEquals(1_700_000_000_000_000_000L, (found as Reattachment.Container).sinceNanos)
        assertEquals(
            listOf(DockerCommands.attachArgs("abc"), DockerCommands.logsArgs("abc", found.sinceNanos, follow = true)),
            started
        )
    }

    @Test
    fun `a stopped container gives its exit code and only the lines after the recorded instant`() {
        val since = 1_700_000_000_000_000_000L
        val docker = DockerRuntime(logger, { _, _ -> throw AssertionError("nothing to attach to") }) { args ->
            when {
                args.contains("{{.State.Running}}") -> DockerRuntime.CommandResult(true, "false\n")
                args.contains("{{.State.ExitCode}}") -> DockerRuntime.CommandResult(true, "143\n")
                args.contains("logs") -> DockerRuntime.CommandResult(
                    true,
                    "2023-11-14T22:13:20.000000000Z seen at the boundary\n" +
                        "2023-11-14T22:13:21.000000000Z Stopping server\n" +
                        "2023-11-14T22:13:22.000000000Z bye"
                )
                else -> DockerRuntime.CommandResult(false, "")
            }
        }

        val record = detachedRecord(runtime = DockerRuntime.ID).copy(logsSince = since)
        val found = docker.reattach("abc", serverDirectory("abc"), record) as Reattachment.Exited

        assertEquals(143, found.exitCode)
        assertEquals(listOf("Stopping server", "bye"), found.replay)
    }

    @Test
    fun `docker leaves a legacy record to legacy adoption`() {
        val docker = DockerRuntime(logger) { DockerRuntime.CommandResult(true, "true") }

        assertNull(docker.reattach("abc", serverDirectory("abc"), detachedRecord(runtime = DockerRuntime.ID).copy(io = null)))
    }

    // ---- The registry booking an exit it missed ---------------------------------------------

    @Test
    fun `an unrequested non-zero exit while away is booked as a crash`() {
        val directory = serverDirectory("crashed")
        val files = DetachedFiles(directory).apply { this.directory.mkdirs() }

        files.exit.writeText("1\n")
        ProcessRecordStore.write(directory, detachedRecord())

        val registry = registry(Reattachment.Exited(1, listOf("java.lang.OutOfMemoryError: Java heap space")))

        registry.load()

        val server = registry.get("crashed")!!

        assertEquals(ServerProcessState.CRASHED, server.state)
        assertEquals(1, server.lastExitCode)
        assertEquals(listOf(ServerProcessState.CRASHED to 1), states)
        assertFalse(server.adopted)
        assertTrue(server.stdinAvailable)

        assertNull(ProcessRecordStore.read(directory))
        assertFalse(files.exit.exists())

        val console = server.console.snapshot().map { it.m }
        assertTrue(console.any { it.contains("OutOfMemoryError") }, "the replay reaches the console")
        assertTrue(console.any { it.contains("exited while the node was down") })
    }

    @Test
    fun `an exit booked while away is handed to the hello once, with its code`() {
        val directory = serverDirectory("announced")

        ProcessRecordStore.write(directory, detachedRecord())
        serverDirectory("untouched")

        val registry = registry(Reattachment.Exited(0, emptyList()))

        registry.load()

        val booked = registry.takeExitsBookedWhileAway()

        assertEquals(listOf("announced"), booked.map { it.uuid })
        assertEquals(0, booked.single().lastExitCode)
        assertTrue(registry.takeExitsBookedWhileAway().isEmpty(), "a reconnect does not announce it again")
    }

    @Test
    fun `a clean exit while away is booked as stopped`() {
        val directory = serverDirectory("stopped")

        ProcessRecordStore.write(directory, detachedRecord())

        val registry = registry(Reattachment.Exited(0, listOf("Stopping server")))

        registry.load()

        assertEquals(ServerProcessState.STOPPED, registry.get("stopped")!!.state)
        assertEquals(0, registry.get("stopped")!!.lastExitCode)
        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `an exit nobody recorded is a crash with an unknown code`() {
        val directory = serverDirectory("unknown")

        ProcessRecordStore.write(directory, detachedRecord())

        val registry = registry(Reattachment.Exited(null, emptyList()))

        registry.load()

        val server = registry.get("unknown")!!

        assertEquals(ServerProcessState.CRASHED, server.state)
        assertNull(server.lastExitCode)
        assertTrue(server.console.snapshot().any { it.m.contains("nothing recorded its exit code") })
    }

    private fun registry(answer: Reattachment?) =
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ReattachRuntime(answer), scheduler, logger, listener)

    /** A process runtime that has already looked, and found [answer]. */
    private class ReattachRuntime(private val answer: Reattachment?) : ServerRuntime {
        override val id = ProcessRuntime.ID

        override fun verify() {}

        override fun launch(request: ServerRuntime.LaunchRequest): Process =
            throw UnsupportedOperationException("no process is ever started in a test")

        override fun terminate(uuid: String, process: Process, timeoutSeconds: Long) = true

        override fun kill(uuid: String, process: Process) {}

        override fun remove(uuid: String) {}

        override fun reattach(uuid: String, directory: File, record: ProcessRecord): Reattachment? = answer

        override fun adopt(uuid: String, directory: File, record: ProcessRecord): AdoptedProcess? =
            throw AssertionError("a detached record must never fall through to legacy adoption")

        override fun pluginEndpoint(nodeEndpoint: com.panomc.node.net.PlatformEndpoint?) = nodeEndpoint

        override fun sample(uuid: String, process: Process, metrics: ServerRuntime.ProcessSampler) =
            ServerRuntime.Sample(null, null)
    }

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    private companion object {
        /** Above any Linux pid_max: never a live process. */
        const val DEAD_PID = 999_999_999L
    }
}
