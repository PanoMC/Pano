package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.files.BackupOrphanSweep
import com.panomc.node.files.TransferService
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.DeleteServerMessage
import com.panomc.node.net.ImportServerMessage
import com.panomc.node.net.ImportServerSpec
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.ExternalServerIndex
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.task.DeleteService
import com.panomc.node.task.ImportService
import com.panomc.node.task.InPlaceAdoption
import com.panomc.node.task.LoaderInstaller
import com.panomc.node.task.TaskSink
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
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
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Adopting an existing server where it is (`IMPORT_SERVER` mode `IN_PLACE`): what it writes, what
 * it refuses, that the next daemon finds it again, and that removing it never deletes the
 * directory it was adopted from.
 *
 * No Minecraft process is started. The "server" is a folder with a jar-named file, a world and a
 * plugin, which is all the adoption and the registry ever look at; "running" is simulated with a
 * held `session.lock` and with a fake process table.
 */
class InPlaceAdoptionTest {
    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var hostDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val vertx = Vertx.vertx()

    @AfterEach
    fun closeVertx() {
        vertx.close()
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private data class Frame(val status: String, val text: String?, val extra: JsonObject?)

    private class RecordingSink : TaskSink {
        val frames = mutableListOf<Frame>()

        override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {
            frames.add(Frame("RUNNING", message, null))
        }

        override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
            frames.add(Frame("DONE", message, extra))
        }

        override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
            frames.add(Frame("FAILED", error, extra))
        }

        fun last() = frames.last()
    }

    private fun registry() =
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener)

    /** A process table with nobody in it, so only the rule under test can refuse. */
    private val nobodyRunning: () -> Sequence<Pair<Long, Path>> = { emptySequence() }

    private fun host(processes: () -> Sequence<Pair<Long, Path>> = nobodyRunning) =
        InPlaceAdoption.Host(dataDir = dataDir, windows = false, home = null, processDirectories = processes)

    private fun importService(registry: ServerRegistry, sink: TaskSink, host: InPlaceAdoption.Host = host()): ImportService {
        val config = NodeConfig()
        val connection = PlatformConnection(vertx, logger, config)
        val reservations = PortReservations()

        return ImportService(
            registry = registry,
            reporter = sink,
            connection = connection,
            transferService = TransferService(registry, config, logger),
            loaderInstaller = LoaderInstaller(JavaRuntimeLocator(dataDir), logger),
            logger = logger,
            dataDir = dataDir,
            reservations = reservations,
            portResolver = PortResolver(registry, reservations, connection, logger) { NODE_PORT_RANGE },
            adoptionHost = host
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** An existing server somebody runs by hand: a jar, a world, a plugin and its own port. */
    private fun existingServer(name: String = "smp", port: Int = freePort()): File {
        val directory = File(hostDir, name).apply { mkdirs() }

        File(directory, "paper-1.21.8.jar").writeBytes(ByteArray(64))
        File(directory, "server.properties").writeText("# my server\nmotd=Hello\nserver-port=$port\n")
        File(directory, "eula.txt").writeText("eula=true\n")
        File(directory, "world").mkdirs()
        File(directory, "world/level.dat").writeText("level")
        File(directory, "plugins").mkdirs()
        File(directory, "plugins/Essentials.jar").writeBytes(ByteArray(16))

        return directory
    }

    private fun adopt(
        service: ImportService,
        path: String?,
        uuid: String = "srv-1",
        port: Int? = 0
    ) = service.import(
        ImportServerMessage(
            serverUuid = uuid,
            taskId = "task-$uuid",
            mode = ImportService.MODE_IN_PLACE,
            folderPath = path,
            spec = ImportServerSpec(name = "SMP", memoryMb = 2048, port = port, acceptEula = true)
        )
    )

    private fun codeOf(frame: Frame): String? = frame.extra?.getString("errorCode")

    // --------------------------------------------------------------------------------- adoption

    @Test
    fun `adopting registers the folder where it is and copies nothing`() {
        val directory = existingServer()
        val properties = File(directory, "server.properties").readText()

        val registry = registry().apply { load() }
        val sink = RecordingSink()

        adopt(importService(registry, sink), directory.absolutePath)

        assertEquals("DONE", sink.last().status, sink.frames.toString())
        assertEquals(true, sink.last().extra?.getBoolean("inPlace"))

        val server = registry.get("srv-1").also { assertNotNull(it) }!!

        assertEquals(directory.toPath().toRealPath(), server.directory.toPath())
        assertTrue(server.spec.inPlace)
        assertEquals("paper", server.spec.software)
        assertTrue(registry.isInPlace("srv-1"))

        // Nothing under the data directory, everything where it was, and server.properties not
        // rewritten because its port was kept.
        assertFalse(File(dataDir, "servers/srv-1").exists())
        assertEquals(properties, File(directory, "server.properties").readText())
        assertEquals("level", File(directory, "world/level.dat").readText())
        assertTrue(File(directory, "plugins/Essentials.jar").isFile)
        assertTrue(File(directory, ServerRegistry.SPEC_FILE).isFile)

        assertEquals(
            mapOf("srv-1" to directory.toPath().toRealPath().toString()),
            ExternalServerIndex.read(dataDir)
        )
    }

    @Test
    fun `a port the admin named replaces the one in server properties`() {
        val directory = existingServer()
        // Inside the node's range: a port Pano asks for outside it is moved into it (NodePortRangeTest).
        val wanted = com.panomc.node.server.PortAllocator.allocate(NODE_PORT_RANGE, emptySet())!!

        val registry = registry().apply { load() }
        val sink = RecordingSink()

        adopt(importService(registry, sink), directory.absolutePath, port = wanted)

        assertEquals("DONE", sink.last().status, sink.frames.toString())
        assertEquals(wanted, registry.get("srv-1")?.spec?.port)
        assertTrue(File(directory, "server.properties").readText().contains("server-port=$wanted"))
        assertTrue(File(directory, "server.properties").readText().contains("motd=Hello"))
    }

    @Test
    fun `the next daemon finds an adopted server again`() {
        val directory = existingServer()

        adopt(importService(registry().apply { load() }, RecordingSink()), directory.absolutePath)

        val reloaded = registry().apply { load() }

        val server = reloaded.get("srv-1").also { assertNotNull(it) }!!

        assertEquals(directory.toPath().toRealPath(), server.directory.toPath())
        assertTrue(server.spec.inPlace)
        assertEquals(directory.toPath().toRealPath(), reloaded.directoryFor("srv-1").toPath())
        assertEquals(setOf("srv-1"), reloaded.externalUuids())
    }

    @Test
    fun `an adopted server whose folder is gone is skipped but not forgotten`() {
        val directory = existingServer()

        adopt(importService(registry().apply { load() }, RecordingSink()), directory.absolutePath)

        directory.deleteRecursively()

        val reloaded = registry().apply { load() }

        assertNull(reloaded.get("srv-1"))
        assertTrue(reloaded.isInPlace("srv-1"))
        assertEquals(setOf("srv-1"), ExternalServerIndex.read(dataDir)?.keys)
    }

    // --------------------------------------------------------------------------------- refusals

    private fun refusal(path: String?, host: InPlaceAdoption.Host = host(), registry: ServerRegistry = registry().apply { load() }): Frame {
        val sink = RecordingSink()

        adopt(importService(registry, sink, host), path)

        return sink.last().also { assertEquals("FAILED", it.status, sink.frames.toString()) }
    }

    @Test
    fun `a relative path is refused`() {
        val frame = refusal("servers/smp")

        assertEquals(InPlaceAdoption.PATH_NOT_ABSOLUTE, codeOf(frame))
        assertTrue(frame.text!!.startsWith("PATH_NOT_ABSOLUTE: "))
    }

    @Test
    fun `a missing path and a file are refused`() {
        assertEquals(InPlaceAdoption.PATH_NOT_FOUND, codeOf(refusal(File(hostDir, "nope").absolutePath)))

        val file = File(hostDir, "file.txt").apply { writeText("x") }

        assertEquals(InPlaceAdoption.PATH_NOT_A_DIRECTORY, codeOf(refusal(file.absolutePath)))
    }

    @Test
    fun `the node's own data folder, anything in it and anything holding it are refused`() {
        val inside = File(dataDir, "servers/other").apply { mkdirs() }

        File(inside, "server.jar").writeBytes(ByteArray(8))

        assertEquals(InPlaceAdoption.PATH_NOT_ALLOWED, codeOf(refusal(inside.absolutePath)))
        assertEquals(InPlaceAdoption.PATH_NOT_ALLOWED, codeOf(refusal(dataDir.absolutePath)))
        assertEquals(InPlaceAdoption.PATH_NOT_ALLOWED, codeOf(refusal(dataDir.parentFile.absolutePath)))
    }

    @Test
    fun `system folders are refused`() {
        assertEquals(InPlaceAdoption.PATH_NOT_ALLOWED, codeOf(refusal("/etc")))
        assertEquals(InPlaceAdoption.PATH_NOT_ALLOWED, codeOf(refusal("/")))

        assertTrue(InPlaceAdoption.isSystemPath("/usr/local/games/mc", windows = false))
        assertTrue(InPlaceAdoption.isSystemPath("/var/lib/minecraft", windows = false))
        assertFalse(InPlaceAdoption.isSystemPath("/var/minecraft", windows = false))
        assertFalse(InPlaceAdoption.isSystemPath("/srv/smp", windows = false))
        assertFalse(InPlaceAdoption.isSystemPath("/usrdata/smp", windows = false))

        assertTrue(InPlaceAdoption.isSystemPath("C:\\", windows = true))
        assertTrue(InPlaceAdoption.isSystemPath("C:\\Windows\\System32", windows = true))
        assertTrue(InPlaceAdoption.isSystemPath("c:\\program files (x86)\\mc", windows = true))
        assertFalse(InPlaceAdoption.isSystemPath("D:\\Servers\\smp", windows = true))
    }

    @Test
    fun `a folder that is already managed is refused`() {
        val directory = existingServer()
        val registry = registry().apply { load() }

        adopt(importService(registry, RecordingSink()), directory.absolutePath)

        val sink = RecordingSink()

        adopt(importService(registry, sink), directory.absolutePath, uuid = "srv-2")

        assertEquals("FAILED", sink.last().status)
        assertEquals(InPlaceAdoption.ALREADY_MANAGED, codeOf(sink.last()))
        assertNull(registry.get("srv-2"))

        // A folder inside it is the same server seen from further down.
        val nested = File(directory, "world")

        File(nested, "server.jar").writeBytes(ByteArray(8))

        assertEquals(InPlaceAdoption.ALREADY_MANAGED, codeOf(refusal(nested.absolutePath, registry = registry)))
    }

    @Test
    fun `a folder carrying another node's server json is refused`() {
        val directory = existingServer()

        File(directory, ServerRegistry.SPEC_FILE).writeText("""{"uuid":"someone-else","jar":"server.jar"}""")

        assertEquals(InPlaceAdoption.ALREADY_MANAGED, codeOf(refusal(directory.absolutePath)))
    }

    @Test
    fun `a running server is refused through its held session lock`() {
        val directory = existingServer()
        val lock = File(directory, "world/session.lock").apply { writeText("") }

        RandomAccessFile(lock, "rw").use { access ->
            access.channel.lock().use {
                val frame = refusal(directory.absolutePath)

                assertEquals(InPlaceAdoption.SERVER_RUNNING, codeOf(frame))
            }
        }

        // Nothing was written into it on the way out.
        assertFalse(File(directory, ServerRegistry.SPEC_FILE).exists())
        assertFalse(ExternalServerIndex.file(dataDir).exists())
    }

    @Test
    fun `a running server is refused through a process working in it`() {
        val directory = existingServer()
        val cwd = directory.toPath().toRealPath()

        val frame = refusal(directory.absolutePath, host(processes = { sequenceOf(4242L to cwd) }))

        assertEquals(InPlaceAdoption.SERVER_RUNNING, codeOf(frame))
        assertTrue(frame.text!!.contains("4242"))
    }

    @Test
    fun `a folder with no jar is refused`() {
        val directory = File(hostDir, "empty").apply { mkdirs() }

        assertEquals(InPlaceAdoption.NO_SERVER_JAR, codeOf(refusal(directory.absolutePath)))
    }

    @Test
    fun `a copy import can never reach an adopted folder`() {
        val directory = existingServer()
        val registry = registry().apply { load() }

        adopt(importService(registry, RecordingSink()), directory.absolutePath)

        val sink = RecordingSink()

        importService(registry, sink).import(
            ImportServerMessage(
                serverUuid = "srv-1",
                taskId = "again",
                mode = ImportService.MODE_FOLDER,
                folderPath = existingServer("other").absolutePath,
                spec = ImportServerSpec(name = "x", port = 0, acceptEula = true)
            )
        )

        assertEquals(InPlaceAdoption.ALREADY_MANAGED, codeOf(sink.last()))
        assertEquals("level", File(directory, "world/level.dat").readText())
    }

    // ------------------------------------------------------------------------------------ delete

    @Test
    fun `deleting an adopted server keeps its folder and removes only what Pano put there`() {
        val directory = existingServer()
        val registry = registry().apply { load() }

        adopt(importService(registry, RecordingSink()), directory.absolutePath)

        ProcessRecordStore.directory(directory).mkdirs()
        File(ProcessRecordStore.directory(directory), "console.log").writeText("line")

        val backups = File(dataDir, "backups/srv-1").apply { mkdirs() }

        File(backups, "b1.zip").writeBytes(ByteArray(32))

        val sink = RecordingSink()

        DeleteService(registry, sink, logger, dataDir).delete(DeleteServerMessage("srv-1", "del"))

        assertEquals("DONE", sink.last().status, sink.frames.toString())
        assertEquals(true, sink.last().extra?.getBoolean("filesKept"))

        assertTrue(directory.isDirectory)
        assertEquals("level", File(directory, "world/level.dat").readText())
        assertTrue(File(directory, "plugins/Essentials.jar").isFile)
        assertTrue(File(directory, "paper-1.21.8.jar").isFile)
        assertTrue(File(directory, "server.properties").isFile)

        assertFalse(File(directory, ServerRegistry.SPEC_FILE).exists())
        assertFalse(ProcessRecordStore.directory(directory).exists())
        assertFalse(backups.exists())

        assertNull(registry.get("srv-1"))
        assertFalse(registry.isInPlace("srv-1"))
        assertFalse(ExternalServerIndex.file(dataDir).exists())

        // And the next daemon does not bring it back.
        assertNull(registry().apply { load() }.get("srv-1"))
    }

    @Test
    fun `a released folder can be adopted again`() {
        val directory = existingServer()
        val registry = registry().apply { load() }

        adopt(importService(registry, RecordingSink()), directory.absolutePath)

        DeleteService(registry, RecordingSink(), logger, dataDir).delete(DeleteServerMessage("srv-1", "del"))

        val sink = RecordingSink()

        adopt(importService(registry, sink), directory.absolutePath, uuid = "srv-3")

        assertEquals("DONE", sink.last().status, sink.frames.toString())
    }

    // ------------------------------------------------------------------------------ orphan sweep

    @Test
    fun `the backup sweep treats adopted servers as registered`() {
        val external = existingServer()

        ExternalServerIndex.write(dataDir, mapOf("ext" to external.absolutePath, "unmounted" to "/mnt/gone/smp"))

        val kept = File(dataDir, "backups/ext").apply { mkdirs() }.also { File(it, "b.zip").writeBytes(ByteArray(8)) }
        val alsoKept = File(dataDir, "backups/unmounted").apply { mkdirs() }
        val orphan = File(dataDir, "backups/orphan").apply { mkdirs() }

        val removed = BackupOrphanSweep.sweep(dataDir, emptySet(), logger)

        assertEquals(listOf("orphan"), removed.map { it.uuid })
        assertTrue(kept.isDirectory)
        assertTrue(alsoKept.isDirectory)
        assertFalse(orphan.exists())
    }

    @Test
    fun `an unreadable index stops the sweep instead of guessing`() {
        ExternalServerIndex.file(dataDir).writeText("this is not json {")

        val orphan = File(dataDir, "backups/maybe-adopted").apply { mkdirs() }

        assertTrue(BackupOrphanSweep.sweep(dataDir, emptySet(), logger).isEmpty())
        assertTrue(orphan.isDirectory)

        val registry = registry().apply { load() }

        assertNull(registry.externalUuids())
    }

    private companion object {
        /** The range this test's node gives its servers. */
        val NODE_PORT_RANGE = 40000..40100
    }
}
