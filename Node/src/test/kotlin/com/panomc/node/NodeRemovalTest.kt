package com.panomc.node

import com.panomc.node.files.BackupOrphanSweep
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.host.ServiceInstaller
import com.panomc.node.host.UninstallEnvironment
import com.panomc.node.net.DeleteServerMessage
import com.panomc.node.net.NodeUninstallMessage
import com.panomc.node.server.PortReservations
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.task.DeleteService
import com.panomc.node.task.TaskSink
import com.panomc.node.task.UninstallService
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deleting a server takes its backups along, a boot sweeps the backups of servers that are gone,
 * and deleting a node uninstalls it from its host (SM-64, §2.4.29 A and B).
 *
 * No Minecraft process is started: every server here is a directory with a `server.json`, which is
 * all the registry, the delete and the uninstall look at. The one process that is spawned is the
 * daemon itself, started on a retired data directory to prove it exits 78 before doing anything.
 */
class NodeRemovalTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private data class Frame(val taskId: String, val status: String, val kind: String, val text: String?, val extra: JsonObject?)

    private class RecordingSink : TaskSink {
        val frames = mutableListOf<Frame>()

        override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {
            frames.add(Frame(taskId, "RUNNING", kind, message, null))
        }

        override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
            frames.add(Frame(taskId, "DONE", kind, message, extra))
        }

        override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
            frames.add(Frame(taskId, "FAILED", kind, error, extra))
        }

        fun last() = frames.last()
    }

    private fun registry() =
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener)

    private fun serverDirectory(uuid: String): File {
        val directory = File(File(dataDir, "servers"), uuid).apply { mkdirs() }

        File(directory, "server.json").writeText(
            """{"uuid":"$uuid","name":"$uuid","software":"paper","jar":"server.jar","memoryMb":1024,"port":25565}"""
        )

        File(directory, "world").mkdirs()
        File(directory, "world/level.dat").writeText("level")

        return directory
    }

    private fun backups(uuid: String): File {
        val directory = File(File(dataDir, "backups"), uuid).apply { mkdirs() }

        File(directory, "b1.zip").writeBytes(ByteArray(2048))
        File(directory, "b1.json").writeText("{}")
        File(directory, "repo/chunks/ab").apply { mkdirs() }.resolve("abcd").writeBytes(ByteArray(512))

        return directory
    }

    // ------------------------------------------------------------------------------ server delete

    @Test
    fun `deleting a server removes its backups too`() {
        val directory = serverDirectory("s1")
        val backups = backups("s1")
        val untouched = backups("s2")

        serverDirectory("s2")

        val registry = registry().apply { load() }
        val sink = RecordingSink()
        val reservations = PortReservations().apply { reserve("s1", 25565) }

        DeleteService(registry, sink, logger, dataDir, reservations).delete(DeleteServerMessage("s1", "t1"))

        assertEquals("DONE", sink.last().status)
        assertTrue(sink.frames.any { it.text == "Deleting backups" })
        assertFalse(directory.exists())
        assertFalse(backups.exists())
        assertTrue(untouched.exists(), "another server's backups must survive")
        assertEquals(null, registry.get("s1"))
        assertEquals(0, reservations.size())
    }

    @Test
    fun `a delete for a server whose directory is already gone still removes its backups`() {
        val backups = backups("gone")

        val sink = RecordingSink()

        DeleteService(registry().apply { load() }, sink, logger, dataDir).delete(DeleteServerMessage("gone", "t2"))

        assertEquals("DONE", sink.last().status)
        assertFalse(backups.exists())
    }

    @Test
    fun `a delete refuses a uuid that is not a directory name`() {
        val sink = RecordingSink()

        DeleteService(registry(), sink, logger, dataDir).delete(DeleteServerMessage("../backups", "t3"))

        assertEquals("FAILED", sink.last().status)
        assertTrue(File(dataDir, "backups").let { !it.exists() || it.isDirectory })
    }

    // ---------------------------------------------------------------------------- orphan sweep

    @Test
    fun `boot sweep removes backups of deleted servers and keeps every live one`() {
        serverDirectory("live")
        backups("live")

        // A server directory whose spec cannot be read is not registered, but it is a server.
        File(File(dataDir, "servers"), "broken").mkdirs()
        backups("broken")

        val orphan = backups("orphan")

        val registry = registry().apply { load() }

        val removed = BackupOrphanSweep.sweep(dataDir, registry.all().map { it.uuid }.toSet(), logger)

        assertEquals(listOf("orphan"), removed.map { it.uuid })
        assertTrue(removed.single().bytes > 0)
        assertFalse(orphan.exists())
        assertTrue(File(dataDir, "backups/live").isDirectory)
        assertTrue(File(dataDir, "backups/broken").isDirectory)
    }

    @Test
    fun `boot sweep with no backups directory does nothing`() {
        assertTrue(BackupOrphanSweep.sweep(dataDir, emptySet(), logger).isEmpty())
    }

    // ------------------------------------------------------------------------------- uninstall

    private fun environment(
        os: String = "linux",
        isRoot: Boolean = false,
        systemdUnit: String? = null,
        jarPath: String? = "/opt/pano-node/pano-node.jar"
    ) = UninstallEnvironment(
        os = os,
        isRoot = isRoot,
        inContainer = false,
        systemdUnit = systemdUnit,
        selfServiceUnit = null,
        jarPath = jarPath,
        dataDir = dataDir.absolutePath,
        user = "pano-node"
    )

    private class Calls {
        var prepared = 0
        var aborted = 0
        var retired = 0
        val forgotten = mutableListOf<String>()
        val commands = mutableListOf<List<String>>()
    }

    private fun uninstallService(
        registry: ServerRegistry,
        sink: TaskSink,
        calls: Calls,
        environment: UninstallEnvironment = environment(),
        keep: List<File> = emptyList()
    ) = UninstallService(
        dataDir = dataDir,
        registry = registry,
        reporter = sink,
        logger = logger,
        environment = { environment },
        serviceInstaller = ServiceInstaller(dataDir, logger),
        prepare = { calls.prepared++ },
        abort = { calls.aborted++ },
        forgetServer = { calls.forgotten.add(it) },
        onRetired = { calls.retired++ },
        platformUrl = { "https://pano.example.com" },
        nodeName = { "test node" },
        keep = { keep },
        commands = UninstallService.CommandRunner { command -> calls.commands.add(command); 0 }
    )

    private fun populate() {
        serverDirectory("a")
        serverDirectory("b")
        backups("a")
        File(dataDir, "java/temurin-21/bin").mkdirs()
        File(dataDir, "java/temurin-21/bin/java").writeBytes(ByteArray(4096))
        File(dataDir, "cache/java").mkdirs()
        File(dataDir, "cache/java/partial.tar.gz").writeBytes(ByteArray(100))
        File(dataDir, "updates").mkdirs()
        File(dataDir, "config.conf").writeText("platform { url = \"x\" }")
    }

    @Test
    fun `uninstall stops and forgets every server, wipes the data and leaves the marker`() {
        populate()

        val registry = registry().apply { load() }
        val sink = RecordingSink()
        val calls = Calls()

        uninstallService(registry, sink, calls).uninstall(NodeUninstallMessage("u1"))

        val done = sink.last()

        assertEquals("DONE", done.status)
        assertEquals(UninstallService.KIND, done.kind)
        assertTrue(done.extra!!.getLong("removedBytes") >= 4096 + 2048 + 100)
        assertTrue(done.extra.getJsonArray("manualSteps").size() > 0)

        assertEquals(1, calls.prepared)
        assertEquals(0, calls.aborted)
        assertEquals(1, calls.retired)
        assertEquals(setOf("a", "b"), calls.forgotten.toSet())
        assertTrue(registry.all().isEmpty())

        UninstallService.DATA_DIRECTORIES.forEach { name ->
            assertFalse(File(dataDir, name).exists(), "$name must be gone")
        }

        assertTrue(RetiredMarker.isRetired(dataDir))
        assertTrue(
            JsonObject(RetiredMarker.file(dataDir).readText()).getString("platformUrl") == "https://pano.example.com"
        )

        // The socket is still authenticated with it until the DONE has left.
        assertTrue(File(dataDir, "config.conf").isFile)

        // What Main does once the daemon stopped.
        assertTrue(RetiredMarker.clearAllBut(dataDir).isEmpty())
        assertEquals(listOf(RetiredMarker.FILE_NAME), dataDir.list()!!.toList())
    }

    @Test
    fun `a second uninstall while one runs is ignored`() {
        populate()

        val sink = RecordingSink()
        val calls = Calls()
        val service = uninstallService(registry().apply { load() }, sink, calls)

        service.uninstall(NodeUninstallMessage("u1"))
        service.uninstall(NodeUninstallMessage("u2"))

        assertTrue(sink.frames.none { it.taskId == "u2" })
        assertEquals(1, calls.retired)
    }

    @Test
    fun `a failed wipe reports FAILED, writes no marker and hands the node back`() {
        assumeTrue(!HostPlatform().isWindowsHost() && System.getProperty("user.name") != "root")

        populate()

        val locked = File(dataDir, "java/temurin-21")

        locked.setWritable(false)

        try {
            val sink = RecordingSink()
            val calls = Calls()

            uninstallService(registry().apply { load() }, sink, calls).uninstall(NodeUninstallMessage("u1"))

            assertEquals("FAILED", sink.last().status)
            assertTrue(sink.last().text!!.contains("could not be removed"))
            assertFalse(RetiredMarker.isRetired(dataDir))
            assertEquals(1, calls.aborted)
            assertEquals(0, calls.retired)
        } finally {
            locked.setWritable(true)
        }
    }

    @Test
    fun `the running jar inside the data directory is kept`() {
        populate()

        val jar = File(dataDir, "updates/pano-node.jar").apply { writeBytes(ByteArray(10)) }

        val sink = RecordingSink()

        uninstallService(registry().apply { load() }, sink, Calls(), keep = listOf(jar))
            .uninstall(NodeUninstallMessage("u1"))

        assertEquals("DONE", sink.last().status)
        assertTrue(jar.isFile)

        RetiredMarker.clearAllBut(dataDir, listOf(jar))

        assertTrue(jar.isFile)
        assertTrue(RetiredMarker.isRetired(dataDir))
        assertEquals(setOf(RetiredMarker.FILE_NAME, "updates"), dataDir.list()!!.toSet())
    }

    @Test
    fun `as root under the install_sh unit the service is disabled and removed`() {
        populate()

        val unit = File(dataDir.parentFile, "unit-${System.nanoTime()}.service").apply { writeText("[Unit]") }
        val installDir = File(dataDir.parentFile, "opt-${System.nanoTime()}/pano-node").apply { mkdirs() }
        val jar = File(installDir, "pano-node.jar").apply { writeBytes(ByteArray(10)) }

        try {
            val sink = RecordingSink()
            val calls = Calls()

            uninstallService(
                registry().apply { load() },
                sink,
                calls,
                environment(isRoot = true, systemdUnit = unit.absolutePath, jarPath = jar.absolutePath)
            ).uninstall(NodeUninstallMessage("u1"))

            assertEquals("DONE", sink.last().status)
            assertTrue(calls.commands.contains(listOf("systemctl", "disable", "pano-node")))
            assertTrue(calls.commands.contains(listOf("systemctl", "daemon-reload")))
            assertFalse(unit.exists())
            assertFalse(installDir.exists())

            // Nothing left to disable; only the data directory, which holds the marker.
            val steps = sink.last().extra!!.getJsonArray("manualSteps").map { it as String }

            assertTrue(steps.none { it.contains("systemctl") }, steps.toString())
            assertEquals(listOf("rm -rf ${dataDir.absolutePath}"), steps)
        } finally {
            unit.delete()
            installDir.parentFile.deleteRecursively()
        }
    }

    // ----------------------------------------------------------------------- retired on start

    @Test
    fun `a daemon started on a retired data directory exits 78 without pairing`() {
        RetiredMarker.write(dataDir, "https://pano.example.com", "old")

        val java = File(File(System.getProperty("java.home"), "bin"), if (HostPlatform().isWindowsHost()) "java.exe" else "java")

        val process = ProcessBuilder(
            java.absolutePath,
            "-cp",
            System.getProperty("java.class.path"),
            "com.panomc.node.Main",
            "--data",
            dataDir.absolutePath,
            "--pano",
            "http://127.0.0.1:9",
            "--bootstrap-token",
            "would-pair-if-it-got-that-far"
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the daemon must exit on its own")
        assertEquals(NodeVersion.RETIRED_EXIT_CODE, process.exitValue(), output)
        assertTrue(output.contains("removed from Pano"), output)

        // It did not even take the lock or write a config.
        assertFalse(File(dataDir, "config.conf").exists())
        assertFalse(File(dataDir, "pano-node.lock").exists())
    }

    @Test
    fun `the self-written systemd unit does not restart a retired node`() {
        assumeTrue(com.panomc.node.host.HostPlatform.isLinux)

        val installer = ServiceInstaller(dataDir, logger)

        installer.install("/opt/pano-node/pano-node.jar", "/usr/bin/java")

        val unit = installer.unitFile().readText()

        assertTrue(unit.contains("RestartPreventExitStatus=78"), unit)
        // A stop (143) and a retirement (78) leave the unit inactive, not failed.
        assertTrue(unit.contains("SuccessExitStatus=75 78 143\n"), unit)
    }

    @Test
    fun `retired marker is detected and cleared around`() {
        assertFalse(RetiredMarker.isRetired(dataDir))

        File(dataDir, "servers/x").mkdirs()
        File(dataDir, "config.conf").writeText("x")

        RetiredMarker.write(dataDir, null, null)

        assertTrue(RetiredMarker.isRetired(dataDir))
        assertNotNull(JsonObject(RetiredMarker.file(dataDir).readText()).getLong("retiredAt"))

        assertTrue(RetiredMarker.clearAllBut(dataDir).isEmpty())
        assertEquals(listOf(RetiredMarker.FILE_NAME), dataDir.list()!!.toList())
    }

    /** Tiny indirection so the Windows guards above read naturally. */
    private class HostPlatform {
        fun isWindowsHost() = com.panomc.node.host.HostPlatform.isWindows
    }
}
