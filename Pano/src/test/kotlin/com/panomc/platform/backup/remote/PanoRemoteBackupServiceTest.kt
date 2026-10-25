package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.server.backup.PanoBackupRetention
import com.panomc.platform.server.backup.ServerPanoBackupSettings
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Pano Backup + transfer flows of [PanoRemoteBackupService] against the fake control plane and
 * in-memory S3 on 127.0.0.1:18472, with a database-free [FakeRunner] producing real archives.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PanoRemoteBackupServiceTest {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val host = FakePanoHost(vertx, 18472)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val passphrase = "correct horse battery staple"

    private lateinit var runner: FakeRunner
    private lateinit var state: MemoryRemoteStateStore
    private lateinit var passphraseFile: PassphraseFile
    private lateinit var service: PanoRemoteBackupService
    private var now = 1_800_000_000_000L

    companion object {
        const val INSTANCE_ID = "7f3c2a10-aaaa-4bbb-8ccc-000000000002"
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

    @Volatile
    private var token: String? = FakePanoHost.TOKEN

    @BeforeEach
    fun setUp() {
        host.backups.clear()
        host.transfers.clear()
        host.transferPendingReads = 0
        host.transferFails = false
        host.transferCompleteQuota = 0
        host.transferProgress = null
        host.aborted.clear()
        host.snapshotRepo.reset()
        host.tokens.add(FakePanoHost.TOKEN)
        host.subscription = true
        host.lapsed = false
        host.quotaBytes = 10L shl 30
        host.partSize = 4096
        host.onPart = { _, _ -> }
        token = FakePanoHost.TOKEN

        runner = FakeRunner(scope)
        state = MemoryRemoteStateStore()
        passphraseFile = PassphraseFile(File(temp, "backups/${PassphraseFile.FILE_NAME}"))
        service = PanoRemoteBackupService(
            client = PanoHostClient({ host.baseUrl }, { token }, { PanoIdentity(INSTANCE_ID, "My Server") }, RetryPolicy(3, 5)),
            stateStore = state,
            passphraseFile = passphraseFile,
            backups = runner,
            tempDir = { File(temp, ".temp") },
            account = { token?.let { ConnectedAccount("tester", "p1") } },
            instanceName = { "My Server" },
            panoVersion = "1.0.0-test",
            clock = { now }
        )
    }

    private fun tempLeftovers() = File(temp, ".temp").listFiles()?.map { it.name } ?: emptyList()

    @Test
    fun `not connected asks to connect, the overview carries account, plan and usage`(): Unit = runBlocking {
        token = null

        val offline = service.status()

        assertFalse(offline.getBoolean("connected"))
        assertNull(offline.getValue("account"))
        assertNull(offline.getValue("plan"))
        assertEquals(PanoHostException.CONNECT_REQUIRED, awaitJob(service.startUpload()).error)
        assertEquals(PanoHostException.CONNECT_REQUIRED, awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT)).error)
        assertEquals(PanoHostException.CONNECT_REQUIRED, assertThrows<PanoHostException> { runBlocking { service.listBackups() } }.code)

        token = FakePanoHost.TOKEN

        val online = service.status()

        assertTrue(online.getBoolean("connected"))
        assertEquals("tester", online.getJsonObject("account").getString("username"))
        assertEquals("backup-10", online.getJsonObject("plan").getJsonObject("tier").getString("id"))
        assertEquals(10L shl 30, online.getJsonObject("usage").getLong("quota"))
        assertEquals(10L shl 30, online.getJsonObject("usage").getLong("free"))
        assertEquals(0L, online.getJsonObject("usage").getLong("used"))
        assertFalse(online.encode().contains(FakePanoHost.TOKEN))

        // Cached for a minute; `fresh` asks again.
        host.subscription = false
        assertNotNull(service.status().getValue("plan"))
        assertNull(service.status(fresh = true).getValue("plan"))

        // Disconnected on panomc.com: the host says so, the overview asks to reconnect.
        host.tokens.remove(FakePanoHost.TOKEN)

        val revoked = service.status(fresh = true)

        assertFalse(revoked.getBoolean("connected"))
        assertEquals(PanoHostException.CONNECT_REQUIRED, revoked.getJsonObject("hostError").getString("code"))
        assertEquals(PanoHostException.INVALID_TOKEN, revoked.getJsonObject("hostError").getString("reason"))

        // The link era's state (tokens of `links`) is ignored and dropped on the next save.
        val legacy = JsonObject().put("links", JsonObject().put("BACKUP", JsonObject().put("purpose", "BACKUP").put("token", "hlt_old")))
            .put("settings", JsonObject().put("schedule", "TIER").put("hour", 5)).put("lastUploadAt", 7L)
        val parsed = RemoteBackupState.parse(legacy.encode())

        assertEquals(RemoteBackupState(RemoteBackupSettings(RemoteBackupSettings.Schedule.DAILY, 5), 7L), parsed)
        assertFalse(parsed.toJson().encode().contains("hlt_old"))
    }

    @Test
    fun `upload is plain without a passphrase, end-to-end encrypted with one, multipart, and restores`(): Unit = runBlocking {
        // No passphrase: recommended, not required — the archive goes up plain.
        val plain = awaitJob(service.startUpload())
        assertEquals(PanoBackupJob.Status.DONE, plain.status, plain.error)
        val plainBytes = host.store.read("backups/${plain.remoteId}")!!
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, PanoArchive.verify(ByteArrayInputStream(plainBytes), PanoArcKeys.NONE).kind)
        assertEquals(listOf(false), runner.archived)
        service.deleteBackup(plain.remoteId!!)
        runner.archived.clear()

        service.setPassphrase(passphrase.toCharArray())
        assertTrue(passphraseFile.isSet())

        host.failPuts.clear()

        val job = awaitJob(service.startUpload())

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)
        assertEquals(PanoBackupJob.Type.UPLOAD, job.type)

        val backupId = job.remoteId!!
        val backup = host.backups.getValue(backupId)
        val bytes = host.store.read("backups/$backupId")!!

        assertEquals("DONE", backup.getString("status"))
        assertEquals("pano-instance", backup.getString("kind"))
        assertEquals(INSTANCE_ID, backup.getString("instanceId"))
        assertEquals(sha256Hex(bytes), backup.getString("sha256"))
        // Started from the panel: the summary says so (the lists show manual / scheduled).
        assertEquals(PanoRemoteBackupService.TRIGGER_MANUAL, backup.getJsonObject("manifest").getString("trigger"))
        assertTrue(bytes.size > 4096 * 4, "several parts")
        assertEquals(bytes.size.toLong(), job.bytesDone)
        assertEquals(listOf(true), runner.archived)
        assertEquals(now, state.state.lastUploadAt)
        assertTrue(tempLeftovers().isEmpty(), "temp files removed: ${tempLeftovers()}")

        // What left this server is an envelope: useless without the passphrase.
        assertEquals(PanoArcException.Code.PASSPHRASE_REQUIRED, assertThrows<PanoArcException> { PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys.NONE) }.code)
        assertEquals(
            PanoArcException.Code.WRONG_PASSPHRASE,
            assertThrows<PanoArcException> { PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys(passphrase = "wrong".toCharArray())) }.code
        )
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys(passphrase = passphrase.toCharArray())).kind)

        // Restore with the saved passphrase.
        val restore = awaitJob(service.startRestore(backupId, null))
        assertEquals(PanoBackupJob.Status.DONE, restore.status, restore.error)
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, runner.restored.single().kind)

        // A wrong passphrase fails the job with the archive's code and restores nothing.
        val wrong = awaitJob(service.startRestore(backupId, "not it".toCharArray()))
        assertEquals("WRONG_PASSPHRASE", wrong.error)
        assertEquals(1, runner.restored.size)
        assertTrue(tempLeftovers().isEmpty())

        service.deleteBackup(backupId)
        assertNull(host.store.read("backups/$backupId"))
    }

    @Test
    fun `host refusals end the job with the host code and its details`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        host.subscription = false

        val payment = awaitJob(service.startUpload())

        assertEquals("PAYMENT_REQUIRED", payment.error)
        assertEquals("NO_SUBSCRIPTION", payment.details?.getString("reason"))

        host.subscription = true
        host.lapsed = true

        val lapsed = awaitJob(service.startUpload())

        assertEquals("PAYMENT_REQUIRED", lapsed.error)
        assertEquals("LAPSED", lapsed.details?.getString("reason"))
        assertNotNull(lapsed.details?.getLong("graceUntil"))

        host.lapsed = false
        host.quotaBytes = 1000

        val quota = awaitJob(service.startUpload())

        assertEquals("QUOTA_EXCEEDED", quota.error)
        assertEquals("QUOTA", quota.details?.getString("reason"))
        assertTrue(tempLeftovers().isEmpty())

        // A part that keeps failing (after the retries) deletes the session at Pano Host.
        host.quotaBytes = 10L shl 30
        host.failAllPuts = true

        try {
            val failing = awaitJob(service.startUpload())

            assertEquals(PanoHostException.UPLOAD_FAILED, failing.error)
            assertNotNull(failing.remoteId)
            assertFalse(host.backups.containsKey(failing.remoteId))
            assertTrue(tempLeftovers().isEmpty())
        } finally {
            host.failAllPuts = false
        }
    }

    @Test
    fun `an upload stopped on the website ends as STOPPED_REMOTELY`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        // Stopped while part 1 is in flight: the poll before part 2 sees CANCELED.
        host.onPart = { key, part -> if (part == 1) host.backups[key.removePrefix("backups/")]?.put("status", "CANCELED") }

        val polled = awaitJob(service.startUpload())

        assertEquals(PanoHostException.STOPPED_REMOTELY, polled.error)
        assertEquals("CANCELED", host.backups.getValue(polled.remoteId!!).getString("status"))
        assertTrue(host.parts["backups/${polled.remoteId}"]?.keys == setOf(1), "no part after the stop")
        assertTrue(tempLeftovers().isEmpty())

        // Stopped mid-part: the aborted upload refuses the PUT, the status explains why.
        host.onPart = { key, part -> if (part == 3) host.stop(key.removePrefix("backups/")) }

        val aborted = awaitJob(service.startUpload())

        assertEquals(PanoHostException.STOPPED_REMOTELY, aborted.error)
        assertEquals(aborted.remoteId, aborted.details?.getString("backupId"))
        assertTrue(host.backups.containsKey(aborted.remoteId), "a stopped backup is not deleted by the Pano")
        assertNull(state.state.lastUploadAt)
    }

    @Test
    fun `transfer pushes a plain archive, then follows the restore until it is done or failed`(): Unit = runBlocking {
        assertEquals(FakePanoHost.WORKLOAD_ID, service.listWorkloads().getJsonArray("workloads").getJsonObject(0).getString("id"))
        assertEquals("WORKLOAD_NOT_FOUND", awaitJob(service.startTransfer("p-other0001", FakePanoHost.TRANSFER_GRANT)).error)

        host.transferPendingReads = 2

        // The code is e-mailed, checked at Pano Host, and only its grant starts a transfer.
        assertEquals("a***@y*****.com", service.requestTransferCode(FakePanoHost.WORKLOAD_ID).getString("email"))
        assertEquals("TRANSFER_CODE_INVALID", assertThrows<PanoHostException> { service.verifyTransferCode(FakePanoHost.WORKLOAD_ID, "000000") }.code)
        assertEquals(FakePanoHost.TRANSFER_GRANT, service.verifyTransferCode(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_CODE))
        assertEquals("TRANSFER_CODE_REQUIRED", awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID, "nope", pollIntervalMs = 10)).error)

        val job = awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT, pollIntervalMs = 10))

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)

        val transferId = job.remoteId!!
        val bytes = host.store.read("imports/$transferId")!!

        assertEquals(listOf(false, false), runner.archived) // the refused grant archived once too
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys.NONE).kind)
        assertEquals("DONE", service.getTransfer(transferId).getString("status"))
        assertEquals(transferId, service.listTransfers().getJsonArray("transfers").getJsonObject(0).getString("id"))

        // A restore that failed on Pano Host fails the job with its message.
        host.transferFails = true
        val failed = awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT, pollIntervalMs = 10))

        assertEquals(PanoRemoteBackupService.TRANSFER_FAILED_CODE, failed.error)
        assertEquals("Restore failed.", failed.message)

        // One that never ends times out; the job keeps no temp file.
        host.transferFails = false
        host.transferPendingReads = Int.MAX_VALUE
        val timedOut = awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT, pollIntervalMs = 10, timeoutMs = -1))

        assertEquals(PanoRemoteBackupService.TRANSFER_TIMEOUT_CODE, timedOut.error)
        assertTrue(tempLeftovers().isEmpty(), "temp files removed: ${tempLeftovers()}")
    }

    @Test
    fun `a transfer reports the percent Pano Host gives while it restores, and nothing when it gives none`(): Unit = runBlocking {
        host.transferPendingReads = 2
        host.transferProgress = 40

        val seen = mutableListOf<Int?>()
        val job = service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT, pollIntervalMs = 40)

        while (job.status == PanoBackupJob.Status.RUNNING) {
            if (job.phase == PanoRemoteBackupService.PHASE_TRANSFER_RESTORING) seen.add(job.percent)

            delay(5)
        }

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)
        assertTrue(seen.contains(40), "percent seen: $seen")
        assertEquals(0L, job.bytesTotal)

        host.transferProgress = null

        val plain = service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT, pollIntervalMs = 10)

        assertEquals(PanoBackupJob.Status.DONE, awaitJob(plain).status)
        assertNull(plain.percent)
    }

    @Test
    fun `a transfer the archive does not fit is refused with the size and cancels itself`(): Unit = runBlocking {
        host.transferCompleteQuota = 1234

        val job = awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID, FakePanoHost.TRANSFER_GRANT, pollIntervalMs = 10))

        assertEquals("QUOTA_EXCEEDED", job.error)
        assertEquals(1234L, job.details?.getLong("maxBytes"))
        assertEquals("CANCELED", host.transfers.values.single().getString("status"))
    }

    @Test
    fun `moving out of Pano Host waits for the export, downloads it and restores it plain`(): Unit = runBlocking {
        assertTrue(service.listHostInstances().getJsonArray("workloads").getJsonObject(0).getBoolean("exportable"))

        val archive = File(temp, "hosted.panoarc").also { runner.archiveTo(it, null) }
        host.exportArchive = archive.readBytes()
        host.exportPendingReads = 2

        val job = awaitJob(service.startHostMove(FakePanoHost.WORKLOAD_ID, pollIntervalMs = 10))

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)
        assertEquals(PanoBackupJob.Type.RESTORE, job.type)
        assertEquals(1, host.exportStarts)
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, runner.restored.single().kind)
        assertTrue(tempLeftovers().isEmpty(), "temp files removed: ${tempLeftovers()}")

        // An export that failed on Pano Host fails the move with its own code; nothing is restored.
        host.exportFails = true
        val failed = awaitJob(service.startHostMove(FakePanoHost.WORKLOAD_ID, pollIntervalMs = 10))

        assertEquals(PanoRemoteBackupService.EXPORT_FAILED_CODE, failed.error)
        assertEquals(1, runner.restored.size)

        // Another account's (or an unknown) instance is not found.
        host.exportFails = false
        assertEquals("WORKLOAD_NOT_FOUND", awaitJob(service.startHostMove("p-other0001", pollIntervalMs = 10)).error)
    }

    @Test
    fun `MC server backups are wrapped as mc-server, encrypted and uploaded per server`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        val zip = Random(9).nextBytes(10_000)
        val meta = JsonObject().put("id", "b-1").put("name", "Nightly").put("serverName", "Survival").put("sha256", sha256Hex(zip))
        var recorded: String? = null
        val source = McServerBackupSource(4, "server-uuid-4", "b-1", meta, onUploaded = { recorded = it }) { it.writeBytes(zip) }

        service.onMcBackupReady(source)

        val job = awaitJob(runner.job!!)

        assertEquals(PanoBackupJob.Type.MC_UPLOAD, job.type)
        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)
        assertEquals(job.remoteId, recorded, "the backup's row learns where its copy went")
        assertEquals("MANUAL", host.backups.getValue(job.remoteId!!).getJsonObject("manifest").getString("trigger"))

        val automatic = awaitJob(service.startMcUpload(McServerBackupSource(4, "server-uuid-4", "b-2", meta, automatic = true) { it.writeBytes(zip) }))

        assertEquals("AUTOMATIC", host.backups.getValue(automatic.remoteId!!).getJsonObject("manifest").getString("trigger"))

        val backup = host.backups.getValue(job.remoteId!!)

        assertEquals("mc-server", backup.getString("kind"))
        assertEquals("server-uuid-4", backup.getString("subject"))
        assertEquals("Survival", backup.getJsonObject("manifest").getString("serverName"))
        assertEquals("Nightly", backup.getJsonObject("manifest").getString("backupName"))

        val bytes = host.store.read("backups/${job.remoteId}")!!
        val target = File(temp, "mc-extract")
        val manifest = PanoArchive.extract(ByteArrayInputStream(bytes), PanoArcKeys(passphrase = passphrase.toCharArray()), target)

        assertEquals(ArchiveManifest.KIND_MC_SERVER, manifest.kind)
        assertArrayEquals(zip, File(target, McServerArchive.ZIP_ENTRY).readBytes())
        assertEquals("Nightly", JsonObject(File(target, McServerArchive.META_ENTRY).readText()).getString("name"))
        assertEquals(PanoArcException.Code.PASSPHRASE_REQUIRED, assertThrows<PanoArcException> { PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys.NONE) }.code)
        assertTrue(tempLeftovers().isEmpty())
    }

    @Test
    fun `the server's retention keeps its last N uploads at Pano Backup and leaves other servers alone`(): Unit = runBlocking {
        val keepTwo = ServerPanoBackupSettings(enabled = true, retention = PanoBackupRetention.COUNT, keepLast = 2)
        val ids = ArrayList<String>()

        fun source(server: Long, backup: String) =
            McServerBackupSource(server, "server-uuid-$server", backup, JsonObject().put("name", backup), keepTwo) { it.writeBytes(Random(backup.hashCode()).nextBytes(500)) }

        val other = awaitJob(service.startMcUpload(source(5, "o-1")))
        assertEquals(PanoBackupJob.Status.DONE, other.status, other.error)

        for (n in 1..3) {
            val job = awaitJob(service.startMcUpload(source(4, "b-$n")))

            assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)
            ids.add(job.remoteId!!)
            kotlinx.coroutines.delay(5)
        }

        val left = service.listMcBackups("server-uuid-4").map { it.getString("id") }

        assertEquals(listOf(ids[2], ids[1]), left)
        assertEquals(listOf(other.remoteId), service.listMcBackups("server-uuid-5").map { it.getString("id") })
    }

    @Test
    fun `an mc-server backup comes back as the node's zip and meta for the importer`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        val zip = Random(11).nextBytes(4_000)
        val meta = JsonObject().put("id", "b-1").put("name", "Before the update").put("scope", "WORLDS")
        val uploaded = awaitJob(service.startMcUpload(McServerBackupSource(4, "server-uuid-4", "b-1", meta) { it.writeBytes(zip) }))

        var received: Pair<ByteArray, JsonObject>? = null
        var into: Long? = null
        val importer = McServerBackupImporter { serverId, file, json, onProgress ->
            into = serverId
            received = file.readBytes() to json
            onProgress(file.length())
            "imported-1"
        }

        val job = awaitJob(service.startMcImport(uploaded.remoteId!!, null, 9, importer))

        assertEquals(PanoBackupJob.Type.MC_IMPORT, job.type)
        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)
        assertEquals("imported-1", job.backupId)
        assertEquals(9L, into)
        assertArrayEquals(zip, received!!.first)
        assertEquals("WORLDS", received!!.second.getString("scope"))
        assertTrue(tempLeftovers().isEmpty(), "temp files removed: ${tempLeftovers()}")

        // A wrong passphrase fails the job before anything reaches a server.
        received = null
        val wrong = awaitJob(service.startMcImport(uploaded.remoteId!!, "not the passphrase".toCharArray(), 9, importer))

        assertEquals(PanoBackupJob.Status.FAILED, wrong.status)
        assertNull(received)
    }

    @Test
    fun `schedule follows the settings, needs a plan and backs off after a failure`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        now = LocalDateTime.of(2026, 9, 27, 23, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        service.tick(now)
        assertTrue(host.backups.isEmpty(), "schedule OFF")
        assertNull(runner.job)

        service.saveSettings(RemoteBackupSettings(schedule = RemoteBackupSettings.Schedule.DAILY, hour = 0, mode = RemoteBackupSettings.Mode.FULL))
        service.tick(now)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(1, host.backups.size)
        assertEquals(PanoRemoteBackupService.TRIGGER_SCHEDULED, host.backups.values.single().getJsonObject("manifest").getString("trigger"))
        assertTrue(service.state().lastFullBytes!! > 0, "the size of the last full archive is remembered")

        val first = runner.job

        service.tick(now + 3 * 3600_000L)
        assertTrue(runner.job === first, "not again the same day")

        // No plan: nothing is archived, and the next attempt waits an hour.
        host.subscription = false
        now += 24 * 3600_000L
        service.status(fresh = true)
        service.tick(now)
        assertTrue(runner.job === first)
        host.subscription = true
        service.status(fresh = true)
        service.tick(now + 10 * 60_000L)
        assertTrue(runner.job === first, "backing off")

        // A failed scheduled upload also backs off (here: the storage refused it, so it waits for the next slot).
        host.quotaBytes = 1000
        service.tick(now + PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS)
        val failed = awaitJob(runner.job!!)
        assertEquals("QUOTA_EXCEEDED", failed.error)
        host.quotaBytes = 10L shl 30
        service.tick(now + PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS + 10 * 60_000L)
        assertTrue(runner.job === failed, "backing off after the failed upload")

        service.tick(now + 2 * PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS + 10 * 60_000L)
        assertTrue(runner.job === failed, "a refused run is not retried within the slot")

        // The plan got bigger (the overview shows more room): it runs at once.
        host.quotaBytes = 20L shl 30
        service.status(fresh = true)
        service.tick(now + 2 * PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS + 10 * 60_000L)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(2, host.backups.values.count { it.getString("status") == "DONE" })
    }

    @Test
    fun `daily schedule uploads on the free quota, an unconnected schedule does nothing`(): Unit = runBlocking {
        host.subscription = false
        host.freeQuotaBytes = 1L shl 30
        now = LocalDateTime.of(2026, 9, 27, 23, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // What setup's "daily backups" switches on: DAILY with the saved hour (default 03:00).
        service.saveSettings(service.state().settings.copy(schedule = RemoteBackupSettings.Schedule.DAILY))
        assertEquals(RemoteBackupSettings(RemoteBackupSettings.Schedule.DAILY, 3, RemoteBackupSettings.Mode.SNAPSHOT), service.state().settings)

        service.saveSettings(service.state().settings.copy(mode = RemoteBackupSettings.Mode.FULL))
        service.tick(now)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(1, host.backups.size)

        // No free plan and no paid one: still refused.
        host.freeQuotaBytes = null
        val first = runner.job
        service.status(fresh = true)
        service.tick(now + 30 * 3600_000L)
        assertTrue(runner.job === first)

        // The account was disconnected: the schedule stays on and the tick silently returns.
        token = null
        service.tick(now + 60 * 3600_000L)
        assertTrue(runner.job === first)
        assertEquals(RemoteBackupSettings.Schedule.DAILY, service.state().settings.schedule)
    }

    @Test
    fun `settings, state and passphrase file`() {
        val daily = RemoteBackupSettings(RemoteBackupSettings.Schedule.DAILY, hour = 4)
        val at = { hour: Int -> LocalDateTime.of(2026, 9, 26, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }

        assertFalse(daily.isDue(null, at(3), ZoneOffset.UTC))
        assertTrue(daily.isDue(null, at(4), ZoneOffset.UTC))
        assertFalse(daily.isDue(at(4), at(4) + 20 * 3600_000L, ZoneOffset.UTC))
        assertTrue(daily.isDue(at(4) + 3600_000L, at(4) + 24 * 3600_000L, ZoneOffset.UTC))
        assertFalse(RemoteBackupSettings().isDue(null, at(12), ZoneOffset.UTC))

        val weekly = RemoteBackupSettings(RemoteBackupSettings.Schedule.WEEKLY, hour = 0)
        assertFalse(weekly.isDue(at(1), at(1) + 5 * 24 * 3600_000L, ZoneOffset.UTC))
        assertTrue(weekly.isDue(at(1), at(1) + 7 * 24 * 3600_000L, ZoneOffset.UTC))
        assertEquals(RemoteBackupSettings.Schedule.DAILY, RemoteBackupSettings.fromJson(JsonObject().put("schedule", "TIER").put("hour", 1))?.schedule)

        assertEquals(daily, RemoteBackupSettings.fromJson(daily.toJson()))

        // `mode`: snapshots by default (also for a state stored before the field), full when chosen, nothing else.
        assertEquals(RemoteBackupSettings.Mode.SNAPSHOT, daily.mode)
        assertEquals(RemoteBackupSettings.Mode.SNAPSHOT, RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 4))?.mode)
        assertEquals(RemoteBackupSettings.Mode.FULL, RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 4), RemoteBackupSettings.Mode.FULL)?.mode)
        assertEquals(RemoteBackupSettings.Mode.FULL, RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 4).put("mode", "FULL"))?.mode)
        assertEquals(RemoteBackupSettings.Mode.SNAPSHOT, RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 4).put("mode", "SNAPSHOT"), RemoteBackupSettings.Mode.FULL)?.mode)
        assertNull(RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 4).put("mode", "DIFF")))
        assertEquals("FULL", daily.copy(mode = RemoteBackupSettings.Mode.FULL).toJson().getString("mode"))
        assertEquals(daily.copy(mode = RemoteBackupSettings.Mode.FULL), RemoteBackupSettings.fromJson(daily.copy(mode = RemoteBackupSettings.Mode.FULL).toJson()))
        assertEquals(
            RemoteBackupState(daily.copy(mode = RemoteBackupSettings.Mode.FULL), 5L, 99L),
            RemoteBackupState.parse(RemoteBackupState(daily.copy(mode = RemoteBackupSettings.Mode.FULL), 5L, 99L).toJson().encode())
        )
        assertEquals(RemoteBackupSettings.Mode.SNAPSHOT, RemoteBackupState.parse(JsonObject().put("settings", JsonObject().put("schedule", "DAILY").put("hour", 2)).encode()).settings.mode)
        assertNull(RemoteBackupSettings.fromJson(JsonObject().put("schedule", "HOURLY").put("hour", 1)))
        assertNull(RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 24)))
        // The old per-Pano list of servers is ignored: each server says it for itself now.
        assertEquals(RemoteBackupSettings(RemoteBackupSettings.Schedule.OFF, 1), RemoteBackupSettings.fromJson(JsonObject().put("schedule", "OFF").put("hour", 1).put("mcServerIds", JsonArray().add(1))))

        assertEquals(RemoteBackupState(), RemoteBackupState.parse("not json"))
        assertEquals(RemoteBackupState(), RemoteBackupState.parse(null))

        val file = PassphraseFile(File(temp, "p/${PassphraseFile.FILE_NAME}"))

        assertFalse(file.isSet())
        file.write("s3cret passphrase".toCharArray())
        assertEquals("s3cret passphrase", String(file.read()!!))

        val perms = java.nio.file.Files.getPosixFilePermissions(File(temp, "p/${PassphraseFile.FILE_NAME}").toPath())
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), perms)

        file.write(null)
        assertFalse(file.isSet())

        assertTrue(PanoRemoteBackupService.isValidRemoteId("0b8e4f6e-3c5d-4a2b-9f1e-2d3c4b5a6f70"))
        assertFalse(PanoRemoteBackupService.isValidRemoteId("../etc"))
        assertFalse(PanoRemoteBackupService.isValidRemoteId(null))
    }
}
