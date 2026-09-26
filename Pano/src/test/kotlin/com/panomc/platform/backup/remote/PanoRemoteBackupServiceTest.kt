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
        host.transfers.clear()
        host.approved.clear()
        host.subscription = true
        host.minIntervalMinutes = 60
        host.partSize = 4096

        runner = FakeRunner(scope)
        state = MemoryRemoteStateStore()
        passphraseFile = PassphraseFile(File(temp, "backups/${PassphraseFile.FILE_NAME}"))
        service = PanoRemoteBackupService(
            client = PanoHostClient({ host.baseUrl }, RetryPolicy(3, 5)),
            stateStore = state,
            passphraseFile = passphraseFile,
            backups = runner,
            tempDir = { File(temp, ".temp") },
            instanceName = { "My Server" },
            panoVersion = "1.0.0-test",
            clock = { now }
        )
    }

    private suspend fun link(purpose: LinkPurpose) {
        val pending = service.startLink(purpose)

        assertEquals("https://panomc.test/host/link?code=ABCD-1234", pending.verifyUrl)
        assertEquals(PanoRemoteBackupService.PENDING, service.pollLink(purpose).getString("status"))

        host.approved[purpose] = true

        val linked = service.pollLink(purpose)

        assertEquals(PanoRemoteBackupService.LINKED, linked.getString("status"))
        assertNull(linked.getJsonObject("link").getString("token"))
    }

    private fun tempLeftovers() = File(temp, ".temp").listFiles()?.map { it.name } ?: emptyList()

    @Test
    fun `link flow keeps the token in the state store and never shows it`(): Unit = runBlocking {
        assertEquals(PanoRemoteBackupService.NONE, service.pollLink(LinkPurpose.BACKUP).getString("status"))

        link(LinkPurpose.BACKUP)

        val stored = state.state.links.getValue(LinkPurpose.BACKUP)

        assertTrue(stored.token.startsWith("hlt_"))
        assertEquals("My Server", stored.instanceName)
        assertEquals(RemoteBackupState.parse(state.state.toJson().encode()), state.state)

        val status = service.status()

        assertFalse(status.encode().contains(stored.token))
        assertEquals(PanoRemoteBackupService.LINKED, service.pollLink(LinkPurpose.BACKUP).getString("status"))

        // An expired request is reported and dropped.
        service.startLink(LinkPurpose.TRANSFER)
        now += 601_000
        assertEquals(PanoRemoteBackupService.EXPIRED, service.pollLink(LinkPurpose.TRANSFER).getString("status"))
        assertEquals(PanoRemoteBackupService.NONE, service.pollLink(LinkPurpose.TRANSFER).getString("status"))

        // A token revoked on panomc.com is forgotten here on its first use.
        host.tokens.remove(stored.token)
        assertEquals(PanoHostException.INVALID_TOKEN, assertThrows<PanoHostException> { runBlocking { service.listBackups() } }.code)
        assertNull(state.state.links[LinkPurpose.BACKUP])

        host.approved.clear()
        link(LinkPurpose.BACKUP)
        service.unlink(LinkPurpose.BACKUP)
        assertNull(state.state.links[LinkPurpose.BACKUP])
    }

    @Test
    fun `upload is end-to-end encrypted, multipart, and restores with the passphrase only`(): Unit = runBlocking {
        link(LinkPurpose.BACKUP)

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
        link(LinkPurpose.BACKUP)
        service.setPassphrase(passphrase.toCharArray())

        host.subscription = false

        val payment = awaitJob(service.startUpload())

        assertEquals("PAYMENT_REQUIRED", payment.error)
        assertEquals("NO_SUBSCRIPTION", payment.details?.getString("reason"))

        host.subscription = true
        assertEquals(PanoBackupJob.Status.DONE, awaitJob(service.startUpload()).status)

        val frequency = awaitJob(service.startUpload())

        assertEquals("QUOTA_EXCEEDED", frequency.error)
        assertEquals("FREQUENCY", frequency.details?.getString("reason"))
        assertNotNull(frequency.details?.getLong("nextAllowedAt"))
        assertTrue(tempLeftovers().isEmpty())

        // A part that keeps failing (after the retries) deletes the session at Pano Host.
        host.minIntervalMinutes = 0
        host.backups.clear()
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
    fun `transfer pushes a plain archive and ends awaiting confirmation`(): Unit = runBlocking {
        assertEquals(PanoRemoteBackupService.NOT_LINKED, awaitJob(service.startTransfer()).error)

        link(LinkPurpose.TRANSFER)

        val job = awaitJob(service.startTransfer())

        assertEquals(PanoBackupJob.Status.DONE, job.status, job.error)

        val transferId = job.remoteId!!
        val bytes = host.store.read("imports/$transferId")!!

        assertEquals(listOf(false), runner.archived)
        assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, PanoArchive.verify(ByteArrayInputStream(bytes), PanoArcKeys.NONE).kind)
        assertEquals("AWAITING_CONFIRMATION", service.getTransfer(transferId).getString("status"))
        assertEquals("p-test00001", service.listTransfers().getJsonObject("workload").getString("id"))
        assertEquals("p-test00001", state.state.links.getValue(LinkPurpose.TRANSFER).workloadId)

        service.cancelTransfer(transferId)
        assertEquals("CANCELED", service.getTransfer(transferId).getString("status"))

        // A TRANSFER link cannot touch Pano Backup.
        assertEquals(PanoRemoteBackupService.NOT_LINKED, assertThrows<PanoHostException> { runBlocking { service.listBackups() } }.code)
    }

    @Test
    fun `MC server backups are wrapped as mc-server, encrypted and uploaded per server`(): Unit = runBlocking {
        link(LinkPurpose.BACKUP)
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
    fun `schedule follows the settings and the tier minimum interval`(): Unit = runBlocking {
        link(LinkPurpose.BACKUP)
        service.setPassphrase(passphrase.toCharArray())

        now = System.currentTimeMillis()
        service.tick(now)
        assertTrue(host.backups.isEmpty(), "schedule OFF")

        service.saveSettings(RemoteBackupSettings(schedule = RemoteBackupSettings.Schedule.TIER))
        service.tick(now)
        awaitJob(runner.job!!)
        assertEquals(1, host.backups.size)

        val first = runner.job

        service.tick(now + 30 * 60_000)
        assertTrue(runner.job === first, "not again within the tier's 60 minutes")

        // The fake enforces the interval against real time; relax it for the second upload.
        host.minIntervalMinutes = 0
        service.tick(now + 61 * 60_000)
        awaitJob(runner.job!!)
        assertTrue(runner.job !== first)
        assertEquals(2, host.backups.size)
    }

    @Test
    fun `settings, state and passphrase file`() {
        val daily = RemoteBackupSettings(RemoteBackupSettings.Schedule.DAILY, hour = 4)
        val at = { hour: Int -> LocalDateTime.of(2026, 9, 26, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }

        assertFalse(daily.isDue(null, 60, at(3), ZoneOffset.UTC))
        assertTrue(daily.isDue(null, 60, at(4), ZoneOffset.UTC))
        assertFalse(daily.isDue(at(4), 60, at(4) + 20 * 3600_000L, ZoneOffset.UTC))
        assertTrue(daily.isDue(at(4) + 3600_000L, 60, at(4) + 24 * 3600_000L, ZoneOffset.UTC))
        // A tier that allows one upload every two days wins over DAILY.
        assertFalse(daily.isDue(at(4), 2 * 24 * 60, at(4) + 25 * 3600_000L, ZoneOffset.UTC))

        val tier = RemoteBackupSettings(RemoteBackupSettings.Schedule.TIER)
        assertTrue(tier.isDue(at(1), 60, at(2), ZoneOffset.UTC))
        assertFalse(tier.isDue(at(1), 60, at(1) + 59 * 60_000L, ZoneOffset.UTC))
        assertFalse(RemoteBackupSettings().isDue(null, 0, at(12), ZoneOffset.UTC))

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
