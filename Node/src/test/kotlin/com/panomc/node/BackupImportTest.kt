package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.files.BackupService
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.BackupImportMessage
import com.panomc.node.net.BackupListMessage
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.task.TaskReporter
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.Sha256
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `BACKUP_IMPORT` (protocol 7): a zip out of Pano Backup becomes a backup of the server, or nothing at all. */
class BackupImportTest {
    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var source: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))
    private val vertx = Vertx.vertx()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }

    @AfterEach
    fun close() {
        vertx.close()
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private fun service(): BackupService {
        val registry = ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener)

        registry.register("s1", File(dataDir, "servers/s1").apply { mkdirs() }, ServerSpec(uuid = "s1"))

        val connection = PlatformConnection(vertx, logger, NodeConfig())

        return BackupService(dataDir, registry, TaskReporter(connection), connection, logger)
    }

    private fun zip(): File = File(source, "backup.zip").also { file ->
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("world/level.dat"))
            it.write("level".toByteArray())
            it.closeEntry()
        }
    }

    /** Serves [file] the way Pano's upload ticket would. */
    private fun fetchOf(file: File): (String, File, Long) -> Long = { _, target, _ -> file.copyTo(target, overwrite = true).length() }

    private fun message(file: File, sha256: String = Sha256.of(file), size: Long = file.length()) = BackupImportMessage(
        serverUuid = "s1", backupId = "b-import", ticket = "t1", name = "Nightly", size = size, sha256 = sha256,
        createdAt = 1234L, scope = "WORLDS"
    )

    @Test
    fun `creating a backup tells its caller whether it worked`() {
        val backups = service()

        fun create(server: String, scope: String?) = backups.create(
            com.panomc.node.net.BackupCreateMessage(
                serverUuid = server, taskId = "t-$server-$scope", backupId = "b-$server-$scope", name = "n",
                mode = "FULL", scope = scope
            )
        )

        // What a schedule's "back up, then restart" must not restart over.
        assertTrue(create("missing", "ALL") != null)
        assertTrue(create("s1", "WORLDS") != null, "a server with no worlds has nothing to back up")

        File(dataDir, "servers/s1/server.properties").writeText("motd=x\n")

        assertEquals(null, create("s1", "ALL"))
    }

    @Test
    fun `a matching zip is kept as a FULL backup of the server`() {
        val backups = service()
        val zip = zip()

        val answer = backups.importBackup(message(zip), fetchOf(zip))

        assertTrue(answer.getBoolean("ok"), answer.encode())

        val listed = backups.list(BackupListMessage(serverUuid = "s1")).getJsonArray("backups").map { it as JsonObject }.single()

        assertEquals("b-import", listed.getString("id"))
        assertEquals("FULL", listed.getString("mode"))
        assertEquals("WORLDS", listed.getString("scope"))
        assertEquals(Sha256.of(zip), listed.getString("sha256"))
        assertTrue(File(dataDir, "backups/s1/b-import.zip").isFile)

        // The same id twice is refused rather than overwritten.
        assertEquals(BackupService.ERROR_BACKUP_EXISTS, backups.importBackup(message(zip), fetchOf(zip)).getString("error"))
    }

    @Test
    fun `a zip that does not match what Pano announced leaves nothing behind`() {
        val backups = service()
        val zip = zip()

        val answer = backups.importBackup(message(zip, sha256 = "0".repeat(64)), fetchOf(zip))

        assertFalse(answer.getBoolean("ok"))
        assertEquals(BackupService.ERROR_IMPORT_MISMATCH, answer.getString("error"))
        assertEquals(emptyList<String>(), File(dataDir, "backups/s1").list()?.toList().orEmpty())

        // An unknown server is refused before anything is fetched.
        assertFalse(backups.importBackup(message(zip).copy(serverUuid = "nobody"), fetchOf(zip)).getBoolean("ok"))
    }

    /** Receives [file] the way a streamed import does: into the part file next to [destination]. */
    private fun receiveOf(file: File): (String, File) -> com.panomc.node.files.TransferService.Received = { _, destination ->
        val part = File(destination.parentFile, destination.name + ".pano-part").also { destination.parentFile.mkdirs() }

        file.copyTo(part, overwrite = true)

        com.panomc.node.files.TransferService.Received(part, part.length(), Sha256.of(part))
    }

    private fun staged(stage: String, sha256: String? = null) = BackupImportMessage(
        serverUuid = "s1", backupId = "b-stream", ticket = "t2", name = "Streamed", size = null, sha256 = sha256,
        createdAt = 99L, scope = "WORLDS", fileCount = 3, stage = stage
    )

    @Test
    fun `a streamed import is received aside, then committed with the meta Pano read last`() {
        val backups = service()
        val zip = zip()
        val released = ArrayList<File>()

        val received = backups.importBackup(staged("RECEIVE"), fetch = { _, _, _ -> error("not the old path") }, receive = receiveOf(zip)) { released.add(it) }

        assertTrue(received.getBoolean("ok"), received.encode())
        assertEquals(zip.length(), received.getLong("sizeBytes"))
        assertEquals(Sha256.of(zip), received.getString("sha256"))
        assertTrue(backups.list(BackupListMessage(serverUuid = "s1")).getJsonArray("backups").none { (it as JsonObject).getString("id") == "b-stream" }, "not a backup before its commit")

        val committed = backups.importBackup(staged("COMMIT", received.getString("sha256")), fetch = { _, _, _ -> 0 }) { released.add(it) }

        assertTrue(committed.getBoolean("ok"), committed.encode())

        val listed = backups.list(BackupListMessage(serverUuid = "s1")).getJsonArray("backups").map { it as JsonObject }.single { it.getString("id") == "b-stream" }

        assertEquals("Streamed", listed.getString("name"))
        assertEquals("WORLDS", listed.getString("scope"))
        assertEquals(1, released.size, "the part's record is dropped once it is in place")
    }

    @Test
    fun `an aborted or broken streamed import leaves nothing`() {
        val backups = service()
        val zip = zip()

        backups.importBackup(staged("RECEIVE"), fetch = { _, _, _ -> 0 }, receive = receiveOf(zip))

        val aborted = backups.importBackup(staged("ABORT"), fetch = { _, _, _ -> 0 })

        assertTrue(aborted.getBoolean("ok"))
        assertEquals(0, dataDir.walkTopDown().count { it.isFile && it.name.contains("b-stream") }, "the part is gone")

        // A part that is no zip is refused at the commit, and deleted.
        val junk = File(source, "junk.bin").apply { writeBytes(ByteArray(100) { 7 }) }

        backups.importBackup(staged("RECEIVE"), fetch = { _, _, _ -> 0 }, receive = receiveOf(junk))

        val refused = backups.importBackup(staged("COMMIT"), fetch = { _, _, _ -> 0 })

        assertFalse(refused.getBoolean("ok"))
        assertEquals(0, dataDir.walkTopDown().count { it.isFile && it.name.contains("b-stream") })

        // A receive that fails (the disk, the link) reports it and keeps nothing.
        val failed = backups.importBackup(staged("RECEIVE"), fetch = { _, _, _ -> 0 }, receive = { _, _ -> throw java.io.IOException("reset") })

        assertEquals("TRANSFER_FAILED", failed.getString("error"))
    }
}
