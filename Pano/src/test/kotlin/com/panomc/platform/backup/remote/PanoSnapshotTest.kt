package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.instance.InstanceArchiver
import com.panomc.platform.archive.instance.InstanceLayout
import com.panomc.platform.backup.PanoBackupJob
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.random.Random

/**
 * Deduplicated snapshots of [PanoRemoteBackupService] against the fake control plane (snapshot routes + in-memory
 * storage with signed pack sizes) on 127.0.0.1:18473, over a real directory tree and a fake database dump.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PanoSnapshotTest {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val host = FakePanoHost(vertx, 18473)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var layout: InstanceLayout
    private lateinit var runner: FakeRunner
    private lateinit var state: MemoryRemoteStateStore
    private lateinit var service: PanoRemoteBackupService
    private val settled = ArrayList<Boolean>()
    private var now = LocalDateTime.of(2026, 10, 10, 23, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val dumpText = buildString { repeat(12_000) { append("INSERT INTO pano_user VALUES ($it, 'user$it', 'hash${it * 31}');\n") } }

    companion object {
        const val INSTANCE_ID = "7f3c2a10-aaaa-4bbb-8ccc-000000000003"
        const val OTHER_ID = "7f3c2a10-aaaa-4bbb-8ccc-0000000000ff"
    }

    @BeforeAll
    fun start() {
        host.start()
    }

    @AfterAll
    fun stop() {
        host.stop()
        vertx.close()
    }

    @BeforeEach
    fun setUp() {
        host.backups.clear()
        host.calls.clear()
        host.tokens.add(FakePanoHost.TOKEN)
        host.subscription = true
        host.lapsed = false
        host.quotaBytes = 10L shl 30
        host.freeQuotaBytes = null
        host.snapshotRepo.reset()
        settled.clear()

        val root = File(temp, "instance-${System.nanoTime()}")

        fun write(path: String, bytes: ByteArray) {
            File(root, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
        }

        write("config.conf", "website-name = \"Test\"\ndatabase { prefix = \"pano_\" }\n".toByteArray())
        write("plugins/a.jar", Random(1).nextBytes(40_000))
        write("plugins/market/config.conf", "x = 1\n".toByteArray())
        write("themes/t/page.html", "<html>hello</html>".toByteArray())
        write("themes/t/.pano-license.jwt", "secret".toByteArray())
        write("file-uploads/world.bin", Random(2).nextBytes(3 shl 20))
        write("file-uploads/empty.txt", ByteArray(0))
        write("file-uploads/cache/c.bin", Random(3).nextBytes(1000))
        write("maintenance/m.html", "<p>back soon</p>".toByteArray())
        Files.createSymbolicLink(File(root, "plugins/link").toPath(), File(root, "config.conf").toPath())

        layout = InstanceLayout(
            configFile = File(root, "config.conf"), pluginsDir = File(root, "plugins"), themesDir = File(root, "themes"),
            uploadsDir = File(root, "file-uploads"), maintenanceDir = File(root, "maintenance"), tempDir = File(root, ".temp")
        )

        runner = newRunner()
        state = MemoryRemoteStateStore()
        service = newService(INSTANCE_ID, runner, state)
    }

    private fun newRunner() = FakeRunner(scope).also { it.layout = layout; it.dumpText = dumpText }

    private fun newService(instanceId: String, runner: FakeRunner, store: MemoryRemoteStateStore) = PanoRemoteBackupService(
        client = PanoHostClient({ host.baseUrl }, { FakePanoHost.TOKEN }, { PanoIdentity(instanceId, "My Server") }, RetryPolicy(3, 5)),
        stateStore = store,
        passphraseFile = null,
        backups = runner,
        tempDir = { File(temp, ".scratch") },
        account = { ConnectedAccount("tester", "p1") },
        instanceName = { "My Server" },
        panoVersion = "1.0.0-test",
        clock = { now },
        onScheduledSettled = { settled.add(it) }
    )

    private fun leftovers() = File(temp, ".scratch").listFiles()?.map { it.name } ?: emptyList()

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                result[entry.name] = zip.readBytes()
            }
        }

        return result
    }

    /** The plain archive Pano makes of the same files today (no database connection, so no dump). */
    private fun archiveOfNow(): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()

        runBlocking { InstanceArchiver(layout, "pano_", "1.0.0-test", ArchiveSource(), "pano/test").archive(out, null, null) }

        return entries(out.toByteArray())
    }

    private fun gunzip(bytes: ByteArray) = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun snapshot(): PanoBackupJob = runBlocking { awaitJob(service.startSnapshot("test")) }

    /** A node backup zip of a small server: a world with a region file, a config and an empty directory. */
    private fun serverZip(region: ByteArray): File = File(temp, "server-${System.nanoTime()}.zip").also { file ->
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray? = null) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                bytes?.let { zip.write(it) }
                zip.closeEntry()
            }

            put("world/")
            put("world/level.dat", "level".toByteArray())
            put("world/region/r.0.0.mca", region)
            put("logs/")
            put("server.properties", "motd=Hi\n".toByteArray())
        }
    }

    /**
     * pano-node's side of a direct upload, in-process: the shared upload code cuts [zip], asking the job's handler what
     * the control plane answers and PUTting to the (fake) storage itself.
     */
    private fun fakeNode(zip: File, sent: MutableList<String>) = McServerDirectUpload { request, handler ->
        sent += if (request.archive) "ARCHIVE" else "SNAPSHOT"

        val storage = PanoHostClient({ host.baseUrl }, { FakePanoHost.TOKEN }, { PanoIdentity(INSTANCE_ID, "My Server") }, RetryPolicy(3, 5)).snapshotRemote
        val remote = object : SnapshotRemote {
            override fun missing(snapshotId: String, chunks: List<Pair<String, Int>>) = runBlocking { handler.missing(snapshotId, chunks) }
            override fun packSlot(snapshotId: String, size: Long) = runBlocking { handler.packSlot(snapshotId, size) }
            override fun putFile(url: String, file: File) = storage.putFile(url, file)
            override fun putBytes(url: String, bytes: ByteArray) = storage.putBytes(url, bytes)
        }
        val stats = kotlinx.coroutines.withContext(Dispatchers.IO) {
            McServerSnapshotUpload(remote, request.snapshot!!, zip, request.meta, File(temp, "node-${System.nanoTime()}"), request.snapshotName, request.createdAt) { done, total ->
                handler.progress("UPLOADING", done, total)
            }.run().stats
        }

        JsonObject().put("ok", true).put("stats", stats.toJson())
    }

    @Test
    fun `a node of protocol 8 sends the server snapshot itself and it comes back the same`(): Unit = runBlocking {
        val region = Random(9).nextBytes(1 shl 20)
        val zip = serverZip(region)
        val sent = ArrayList<String>()
        val source = McServerBackupSource(
            4, "server-uuid-4", "b-1", JsonObject().put("id", "b-1").put("name", "Hourly").put("mode", "SNAPSHOT").put("scope", "ALL"),
            direct = fakeNode(zip, sent)
        ) { error("A direct upload never pulls the backup to Pano.") }

        val job = awaitJob(service.startMcUpload(source))

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error + " " + job.message)
        assertEquals(listOf("SNAPSHOT"), sent)

        var files: Map<String, ByteArray>? = null
        val imported = awaitJob(service.startMcImport(job.remoteId!!, null, 9, McServerBackupImporter { _, back, _, _ ->
            files = entries(back.readBytes())
            "imported"
        }, snapshot = true))

        assertEquals(PanoBackupJob.Status.DONE, imported.status, imported.error + " " + imported.message)
        assertArrayEquals(region, files!!.getValue("world/region/r.0.0.mca"))
    }

    @Test
    fun `a server snapshot goes into the repository with its server as subject and comes back as the node's zip`(): Unit = runBlocking {
        val region = Random(7).nextBytes(2 shl 20)
        val settings = com.panomc.platform.server.backup.ServerPanoBackupSettings(
            enabled = true, mode = com.panomc.platform.server.backup.BackupMode.SNAPSHOT, keepLast = 1
        )

        val recorded = ArrayList<String>()

        fun source(id: String, zip: File) = McServerBackupSource(
            4, "server-uuid-4", id, JsonObject().put("id", id).put("name", "Hourly").put("mode", "SNAPSHOT").put("scope", "ALL").put("serverName", "Lobby #1"), settings,
            onUploaded = { recorded += it }
        ) { it.writeBytes(zip.readBytes()) }

        val first = awaitJob(service.startMcUpload(source("b-1", serverZip(region))))

        assertEquals(PanoBackupJob.Status.DONE, first.status, first.error + " " + first.message)
        assertEquals(listOf(first.remoteId), recorded)
        assertEquals("MANUAL", service.listMcBackups("server-uuid-4").single().getString("trigger"))
        assertEquals("Lobby #1", service.listMcBackups("server-uuid-4").single().getString("subjectName"), "the server's name goes with the snapshot")
        assertEquals(PanoBackupJob.Type.MC_UPLOAD, first.type)

        // The same world again: only what changed is new, and the server keeps its last one only.
        val changed = region.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val second = awaitJob(service.startMcUpload(source("b-2", serverZip(changed))))

        assertEquals(PanoBackupJob.Status.DONE, second.status, second.error + " " + second.message)

        val rows = service.listMcBackups("server-uuid-4")

        assertEquals(listOf(second.remoteId), rows.map { it.getString("id") })
        assertEquals(PanoRemoteBackupService.TYPE_SNAPSHOT, rows.single().getString("type"))
        assertEquals("mc-server", rows.single().getString("kind"))

        // Back as the zip the node had: same files, same bytes.
        var received: Pair<Map<String, ByteArray>, JsonObject>? = null
        val imported = awaitJob(service.startMcImport(second.remoteId!!, null, 9, McServerBackupImporter { serverId, zip, meta, _ ->
            assertEquals(9L, serverId)
            received = entries(zip.readBytes()) to meta
            "imported"
        }, snapshot = true))

        assertEquals(PanoBackupJob.Status.DONE, imported.status, imported.error + " " + imported.message)
        assertEquals("imported", imported.backupId)

        val (files, meta) = received!!

        assertArrayEquals(changed, files.getValue("world/region/r.0.0.mca"))
        assertArrayEquals("level".toByteArray(), files.getValue("world/level.dat"))
        assertTrue(files.containsKey("logs/"), "empty directories come back: ${files.keys}")
        assertEquals("Hourly", meta.getString("name"))
        assertEquals("server-uuid-4", meta.getString("serverUuid"))

        // A server snapshot is never restored over the Pano itself.
        assertEquals(PanoBackupJob.Status.FAILED, awaitJob(service.startSnapshotRestore(second.remoteId!!)).status)
        assertTrue(leftovers().isEmpty(), "scratch left: ${leftovers()}")
    }

    @Test
    fun `a snapshot restores into the archive Pano would have written, and downloadBytes is its size`(): Unit = runBlocking {
        val job = snapshot()

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error + " " + job.message)
        assertEquals(PanoBackupJob.Type.SNAPSHOT, job.type)
        assertNotNull(job.remoteId)
        assertEquals(INSTANCE_ID, host.snapshotRepo.views(INSTANCE_ID).single().getString("instanceId"))
        assertEquals("DONE", host.snapshotRepo.views(INSTANCE_ID).single().getString("status"))
        assertEquals(1, host.snapshotRepo.manifestPuts.size)
        assertTrue(host.snapshotRepo.packPuts.size >= 2, "a 3 MiB file does not fit one 1 MiB pack: ${host.snapshotRepo.packPuts.size}")
        assertEquals(now, state.state.lastUploadAt)
        assertTrue(leftovers().isEmpty(), "scratch removed: ${leftovers()}")
        assertFalse(host.snapshotRepo.completes.isEmpty())

        val restore = awaitJob(service.startSnapshotRestore(job.remoteId!!, runner))

        assertEquals(PanoBackupJob.Status.DONE, restore.status, restore.error + " " + restore.message)
        assertTrue(leftovers().isEmpty(), "restore scratch removed: ${leftovers()}")

        val restored = runner.restoredEntries
        val reference = archiveOfNow()

        assertEquals("pano-instance", runner.restored.single().kind)
        assertEquals("1.0.0-test", runner.restored.single().pano!!.version)
        assertEquals("pano_", runner.restored.single().pano!!.dbPrefix)
        assertEquals(mapOf("core" to 5), runner.restored.single().pano!!.schemeVersions)
        assertEquals("pano-native", runner.restored.single().db!!.dumpTool)

        // Every file of the archive Pano writes from the same state is there, byte for byte; nothing excluded came along.
        val files = reference.keys.filter { it != "manifest.json" }

        assertTrue(files.contains("app/config.conf") && files.contains("app/file-uploads/world.bin") && files.contains("app/file-uploads/empty.txt"))
        assertFalse(files.any { it.contains(".pano-license.jwt") || it.contains("/cache/") || it.endsWith("plugins/link") })
        assertEquals(files.sorted(), restored.keys.filter { it != "manifest.json" && it != "db/dump.sql.gz" }.sorted())
        files.forEach { assertTrue(reference.getValue(it).contentEquals(restored.getValue(it)), "$it differs") }
        assertEquals(dumpText, String(gunzip(restored.getValue("db/dump.sql.gz"))))

        // The link was skipped, as in the archive.
        assertTrue(runner.restored.single().skipped.contains("app/plugins/link"), "${runner.restored.single().skipped}")

        // `downloadBytes` told the server what the plain archive of this snapshot weighs.
        assertEquals(runner.restoredSize, host.snapshotRepo.completes.single().getLong("downloadBytes"))
        assertEquals(restored.filterKeys { it.startsWith("app/") }.values.sumOf { it.size.toLong() } + dumpText.length + metaSize(), host.snapshotRepo.completes.single().getLong("logicalBytes"))
    }

    private fun metaSize(): Long = runBlocking {
        // meta/pano.json is part of the logical size; rebuild it the way the upload does.
        val content = SnapshotContent(layout, "pano_", "1.0.0-test", mapOf("core" to 5), "pano-native", null)
        val skipped = SnapshotFiles(layout).skipped

        SnapshotArc.meta(content, service.producer, skipped).encode().toByteArray().size.toLong()
    }

    @Test
    fun `a snapshot downloads as the plain archive a restore would take, once, and the scratch goes`(): Unit = runBlocking {
        val taken = snapshot()
        val job = awaitJob(service.startSnapshotDownload(taken.remoteId!!))

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error + " " + job.message)
        assertEquals(PanoBackupJob.Type.DOWNLOAD, job.type)
        assertTrue(runner.restored.isEmpty(), "nothing is restored by a download")

        val download = service.takeDownload(job.id)!!

        assertTrue(download.fileName.endsWith(".panoarc"), download.fileName)
        assertTrue(download.file.isFile && download.file.length() > 0)
        assertEquals(listOf(download.file.name), leftovers(), "only the archive is left, no work folder")
        assertNull(service.takeDownload("00000000-0000-4000-8000-000000000000"), "another job id gets nothing")

        service.discardDownload(job.id)

        assertNull(service.takeDownload(job.id))
        assertTrue(leftovers().isEmpty(), "removed once sent: ${leftovers()}")
    }

    @Test
    fun `a download expires, a failed one leaves nothing`(): Unit = runBlocking {
        val job = awaitJob(service.startSnapshotDownload(host.snapshotRepo.plantOther("other", "x")))

        // The planted snapshot has no manifest to open: the job fails and cleans up.
        assertEquals(PanoBackupJob.Status.FAILED, job.status)
        assertNull(service.takeDownload(job.id))
        assertTrue(leftovers().isEmpty(), "${leftovers()}")

        val ok = awaitJob(service.startSnapshotDownload(snapshot().remoteId!!))

        assertNotNull(service.takeDownload(ok.id))
        assertNull(service.takeDownload(ok.id, now + PanoRemoteBackupService.DOWNLOAD_TTL_MS + 1))

        service.tick(now + PanoRemoteBackupService.DOWNLOAD_TTL_MS + 1)

        assertTrue(leftovers().isEmpty(), "swept: ${leftovers()}")
    }

    @Test
    fun `a second snapshot of unchanged content uploads no pack, a changed file only its chunks`(): Unit = runBlocking {
        val first = snapshot()
        val packs = host.snapshotRepo.packPuts.size

        assertEquals(PanoBackupJob.Status.DONE, first.status, first.error)

        val second = snapshot()

        assertEquals(PanoBackupJob.Status.DONE, second.status, second.error)
        assertEquals(packs, host.snapshotRepo.packPuts.size, "nothing new to pack")
        assertEquals(2, host.snapshotRepo.manifestPuts.size)
        assertEquals(0L, host.snapshotRepo.completes.last().getLong("newChunks"))
        assertEquals(0L, host.snapshotRepo.completes.last().getLong("newBytes"))
        assertEquals(host.snapshotRepo.completes.first().getLong("chunkCount"), host.snapshotRepo.completes.last().getLong("chunkCount"))

        // Sixteen bytes in the middle of the big file: a chunk or two, not the file.
        File(layout.uploadsDir, "world.bin").also { file ->
            val bytes = file.readBytes()

            for (i in 0 until 16) bytes[1_500_000 + i] = (bytes[1_500_000 + i].toInt() xor 0x7f).toByte()

            file.writeBytes(bytes)
        }

        val third = snapshot()
        val body = host.snapshotRepo.completes.last()

        assertEquals(PanoBackupJob.Status.DONE, third.status, third.error)
        assertTrue(body.getLong("newChunks") in 1..3, "changed chunks: ${body.getLong("newChunks")}")
        assertEquals(packs + 1, host.snapshotRepo.packPuts.size, "one small pack")

        // The restore of the newest one has the new bytes, the first one the old.
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(service.startSnapshotRestore(third.remoteId!!, runner)).status)
        assertTrue(File(layout.uploadsDir, "world.bin").readBytes().contentEquals(runner.restoredEntries.getValue("app/file-uploads/world.bin")))
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(service.startSnapshotRestore(first.remoteId!!, runner)).status)
        assertFalse(File(layout.uploadsDir, "world.bin").readBytes().contentEquals(runner.restoredEntries.getValue("app/file-uploads/world.bin")))
    }

    @Test
    fun `a full storage refuses at the start before any work`(): Unit = runBlocking {
        host.quotaBytes = 0

        val job = snapshot()

        assertEquals(PanoBackupJob.Status.FAILED, job.status)
        assertEquals("QUOTA_EXCEEDED", job.error)
        assertEquals("QUOTA", job.details!!.getString("reason"))
        assertTrue(host.snapshotRepo.chunkQueries.isEmpty(), "no chunk was even read")
        assertTrue(host.snapshotRepo.snapshotIds().isEmpty())
        assertTrue(leftovers().isEmpty(), "${leftovers()}")
        assertFalse(host.calls.any { it.startsWith("DELETE /host/connected/snapshots") }, "nothing was started, nothing to delete")
    }

    @Test
    fun `a quota that runs out at a pack aborts the snapshot and cleans up`(): Unit = runBlocking {
        host.quotaBytes = 600_000

        val job = snapshot()

        assertEquals(PanoBackupJob.Status.FAILED, job.status)
        assertEquals("QUOTA_EXCEEDED", job.error)
        assertTrue(host.snapshotRepo.chunkQueries.isNotEmpty(), "it got as far as packing")
        assertTrue(host.snapshotRepo.packPuts.isEmpty())
        assertTrue(host.calls.any { it == "DELETE /host/connected/snapshots/${job.remoteId}" }, "the open snapshot was deleted")
        assertTrue(host.snapshotRepo.snapshotIds().isEmpty())
        assertTrue(leftovers().isEmpty(), "${leftovers()}")
        assertNull(state.state.lastUploadAt)
    }

    @Test
    fun `a snapshot the server fails in complete is a failure with its error`(): Unit = runBlocking {
        host.snapshotRepo.failComplete = "INTEGRITY_FAILED"

        val job = snapshot()

        assertEquals(PanoBackupJob.Status.FAILED, job.status)
        assertEquals(PanoRemoteBackupService.SNAPSHOT_FAILED, job.error)
        assertEquals("INTEGRITY_FAILED", job.details!!.getString("error"))
        assertEquals("INTEGRITY_FAILED", job.message)
        assertTrue(host.snapshotRepo.snapshotIds().isEmpty(), "deleted")
        assertTrue(leftovers().isEmpty(), "${leftovers()}")
        assertNull(state.state.lastUploadAt)
    }

    @Test
    fun `an upload the storage refuses ends the job and deletes the snapshot`(): Unit = runBlocking {
        host.failAllPuts = true

        try {
            val job = snapshot()

            assertEquals(PanoBackupJob.Status.FAILED, job.status)
            assertEquals(PanoHostException.UPLOAD_FAILED, job.error)
            assertTrue(host.snapshotRepo.snapshotIds().isEmpty())
            assertTrue(leftovers().isEmpty(), "${leftovers()}")
        } finally {
            host.failAllPuts = false
        }
    }

    @Test
    fun `a second snapshot while one is open is busy`(): Unit = runBlocking {
        val client = PanoHostClient({ host.baseUrl }, { FakePanoHost.TOKEN }, { PanoIdentity(INSTANCE_ID, "My Server") })
        val open = client.startSnapshot(null)

        val busy = assertThrows<PanoHostException> { runBlocking { client.startSnapshot(null) } }

        assertEquals("SNAPSHOT_BUSY", busy.code)
        assertEquals(open.id, busy.extras.getString("snapshotId"))

        client.deleteSnapshot(open.id)
    }

    @Test
    fun `the list holds this Pano's snapshots and the account's others, delete removes one`(): Unit = runBlocking {
        val mine = snapshot()
        val other = host.snapshotRepo.plantOther(OTHER_ID, "Their one")

        val listed = service.listSnapshots()
        val views = listed.getJsonArray("snapshots").map { it as JsonObject }

        assertEquals(setOf(mine.remoteId, other), views.map { it.getString("id") }.toSet())
        assertTrue(views.single { it.getString("id") == mine.remoteId }.getBoolean("own"))
        assertFalse(views.single { it.getString("id") == other }.getBoolean("own"))
        assertTrue(listed.getJsonObject("repository").getLong("storedBytes") > 0)

        service.deleteSnapshot(mine.remoteId!!)

        assertEquals(listOf(other), service.listSnapshots().getJsonArray("snapshots").map { (it as JsonObject).getString("id") })
    }

    @Test
    fun `a snapshot of another Pano of the account restores here`(): Unit = runBlocking {
        val otherRunner = newRunner()
        val other = newService(OTHER_ID, otherRunner, MemoryRemoteStateStore())
        val job = awaitJob(other.startSnapshot("theirs"))

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)

        val restore = awaitJob(service.startSnapshotRestore(job.remoteId!!, runner))

        assertEquals(PanoBackupJob.Status.DONE, restore.status, restore.error + " " + restore.message)
        assertEquals(dumpText, String(gunzip(runner.restoredEntries.getValue("db/dump.sql.gz"))))
        assertTrue(runner.restoredEntries.containsKey("app/config.conf"))
    }

    @Test
    fun `a damaged pack fails the restore with INTEGRITY_FAILED and leaves nothing behind`(): Unit = runBlocking {
        val job = snapshot()

        host.snapshotRepo.corruptAPack()

        val restore = awaitJob(service.startSnapshotRestore(job.remoteId!!, runner))

        assertEquals(PanoBackupJob.Status.FAILED, restore.status)
        assertEquals(PanoHostException.INTEGRITY_FAILED, restore.error)
        assertTrue(runner.restored.isEmpty(), "nothing reached the restore")
        assertTrue(leftovers().isEmpty(), "${leftovers()}")
    }

    @Test
    fun `the schedule takes a snapshot by default and a full archive when chosen`(): Unit = runBlocking {
        service.saveSettings(RemoteBackupSettings(schedule = RemoteBackupSettings.Schedule.DAILY, hour = 0))
        service.tick(now)

        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(PanoBackupJob.Type.SNAPSHOT, runner.job!!.type)
        assertEquals(1, host.snapshotRepo.snapshotIds().size)
        assertTrue(host.backups.isEmpty())
        assertEquals(now, state.state.lastUploadAt)
        assertEquals("Scheduled", host.snapshotRepo.views(INSTANCE_ID).single().getString("name"))

        service.saveSettings(service.state().settings.copy(mode = RemoteBackupSettings.Mode.FULL))
        now += 25 * 3600_000L
        service.tick(now)

        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(PanoBackupJob.Type.UPLOAD, runner.job!!.type)
        assertEquals(1, host.backups.size)
    }

    @Test
    fun `a scheduled snapshot the storage refused waits for the next slot and for more room`(): Unit = runBlocking {
        service.saveSettings(RemoteBackupSettings(schedule = RemoteBackupSettings.Schedule.DAILY, hour = 0))
        host.quotaBytes = 0

        service.tick(now)

        val refused = awaitJob(runner.job!!)

        assertEquals("QUOTA_EXCEEDED", refused.error)
        assertTrue(host.snapshotRepo.chunkQueries.isEmpty())

        // Not hourly, not every tick: nothing runs again within the slot.
        for (hours in listOf(1, 2, 6, 12, 20)) {
            service.tick(now + hours * 3600_000L)
            assertTrue(runner.job === refused, "no retry after $hours h")
        }

        // The next slot tries again (and is refused again).
        val slot = now + 25 * 3600_000L

        now = slot
        service.tick(now)

        val again = runner.job!!

        assertTrue(again !== refused, "the next slot ran")
        assertEquals("QUOTA_EXCEEDED", awaitJob(again).error)

        // More room (a bigger plan, or backups deleted) lifts the hold at once.
        host.quotaBytes = 10L shl 30
        now += 30 * 60_000L
        service.tick(now)

        assertTrue(runner.job !== again, "the overview shows more room: no waiting for the slot")
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
    }

    @Test
    fun `a settings change lifts the hold, and a full run that cannot fit is skipped without archiving`(): Unit = runBlocking {
        // Learn the size of a full archive first.
        service.saveSettings(RemoteBackupSettings(schedule = RemoteBackupSettings.Schedule.DAILY, hour = 0, mode = RemoteBackupSettings.Mode.FULL))
        service.tick(now)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)

        val first = runner.job!!
        val archives = runner.archived.size
        val size = service.state().lastFullBytes!!

        // Next day: less free space than that archive. Skipped, no archive is made, the failure is recorded once.
        host.quotaBytes = size / 2 + 2000
        now += 25 * 3600_000L
        service.status(fresh = true)
        service.tick(now)

        assertTrue(runner.job === first)
        assertEquals(archives, runner.archived.size)
        assertEquals(listOf(true, false), settled, "the first run, then the skipped one")

        service.tick(now + 3600_000L)
        service.tick(now + 2 * 3600_000L)
        assertEquals(listOf(true, false), settled, "not again within the slot")

        // The owner changes the settings: it runs again at once (and fits nowhere, so it is skipped again).
        host.quotaBytes = 10L shl 30
        service.saveSettings(service.state().settings.copy(mode = RemoteBackupSettings.Mode.SNAPSHOT))
        now += 3 * 3600_000L
        service.tick(now)

        assertTrue(runner.job !== first)
        assertEquals(PanoBackupJob.Type.SNAPSHOT, runner.job!!.type)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
    }
}
