package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.backup.PanoBackupJob
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
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
        host.aborted.clear()
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
        assertEquals(PanoHostException.CONNECT_REQUIRED, awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID)).error)
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
    fun `upload is end-to-end encrypted, multipart, and restores with the passphrase only`(): Unit = runBlocking {
        // No passphrase yet → refused inside the job, nothing uploaded.
        val refused = awaitJob(service.startUpload())
        assertEquals("PASSPHRASE_NOT_SET", refused.error)
        assertTrue(host.backups.isEmpty())

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
    fun `transfer pushes a plain archive into the chosen workload and ends awaiting confirmation`(): Unit = runBlocking {
        assertEquals(FakePanoHost.WORKLOAD_ID, service.listWorkloads().getJsonArray("workloads").getJsonObject(0).getString("id"))
        assertEquals("WORKLOAD_NOT_FOUND", awaitJob(service.startTransfer("p-other0001")).error)

        val job = awaitJob(service.startTransfer(FakePanoHost.WORKLOAD_ID))

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)

        val transferId = job.remoteId!!
        val bytes = host.store.read("imports/$transferId")!!

        assertEquals(listOf(false), runner.archived)
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys.NONE).kind)
        assertEquals("AWAITING_CONFIRMATION", service.getTransfer(transferId).getString("status"))
        assertEquals(transferId, service.listTransfers().getJsonArray("transfers").getJsonObject(0).getString("id"))

        service.cancelTransfer(transferId)
        assertEquals("CANCELED", service.getTransfer(transferId).getString("status"))
    }

    @Test
    fun `MC server backups are wrapped as mc-server, encrypted and uploaded per server`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        val zip = Random(9).nextBytes(10_000)
        val meta = JsonObject().put("id", "b-1").put("name", "Nightly").put("sha256", sha256Hex(zip))
        val source = McServerBackupSource(4, "server-uuid-4", "b-1", meta) { it.writeBytes(zip) }

        // Not selected in the settings → ignored.
        service.onMcBackupReady(source)
        kotlinx.coroutines.delay(100)
        assertTrue(host.backups.isEmpty())

        service.saveSettings(RemoteBackupSettings(mcServerIds = listOf(4)))
        service.onMcBackupReady(source)

        val job = awaitJob(runner.job!!)

        assertEquals(PanoBackupJob.Type.MC_UPLOAD, job.type)
        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)

        val backup = host.backups.getValue(job.remoteId!!)

        assertEquals("mc-server", backup.getString("kind"))
        assertEquals("server-uuid-4", backup.getString("subject"))

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
    fun `schedule follows the settings, needs a plan and backs off after a failure`(): Unit = runBlocking {
        service.setPassphrase(passphrase.toCharArray())

        now = LocalDateTime.of(2026, 9, 27, 23, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        service.tick(now)
        assertTrue(host.backups.isEmpty(), "schedule OFF")
        assertNull(runner.job)

        service.saveSettings(RemoteBackupSettings(schedule = RemoteBackupSettings.Schedule.DAILY, hour = 0))
        service.tick(now)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(1, host.backups.size)

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

        // A failed scheduled upload also backs off.
        host.quotaBytes = 1000
        service.tick(now + PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS)
        val failed = awaitJob(runner.job!!)
        assertEquals("QUOTA_EXCEEDED", failed.error)
        host.quotaBytes = 10L shl 30
        service.tick(now + PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS + 10 * 60_000L)
        assertTrue(runner.job === failed, "backing off after the failed upload")

        service.tick(now + 2 * PanoRemoteBackupService.RETRY_AFTER_FAILURE_MS + 10 * 60_000L)
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(runner.job!!).status)
        assertEquals(2, host.backups.values.count { it.getString("status") == "DONE" })
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
        assertNull(RemoteBackupSettings.fromJson(JsonObject().put("schedule", "HOURLY").put("hour", 1)))
        assertNull(RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 24)))
        assertNull(RemoteBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 1).put("mcServerIds", JsonArray().add("x"))))
        assertEquals(listOf(1L, 2L), RemoteBackupSettings.fromJson(JsonObject().put("schedule", "OFF").put("hour", 1).put("mcServerIds", JsonArray().add(1).add(2).add(1)))?.mcServerIds)

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
