package com.panomc.platform.backup.remote

import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/** The HTTP side of the Pano Backup client against a fake control plane + in-memory S3 on 127.0.0.1:18471. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PanoHostClientTest {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val host = FakePanoHost(vertx, 18471)
    private val client = PanoHostClient({ host.baseUrl }, RetryPolicy(attempts = 3, baseDelayMs = 10))

    @BeforeAll
    fun start() {
        host.start()
    }

    @AfterAll
    fun stop() {
        host.stop()
        vertx.close()
    }

    private suspend fun linkToken(purpose: LinkPurpose): String {
        host.approved.clear()

        val start = client.linkStart("Test", purpose)

        assertEquals("ABCD-1234", start.getString("code"))
        assertEquals("PENDING", client.linkPoll(start.getString("pollToken")).getString("status"))

        host.approved[purpose] = true

        val active = client.linkPoll(start.getString("pollToken"))

        assertEquals("ACTIVE", active.getString("status"))

        return active.getString("token")
    }

    @Test
    fun `link flow, bearer auth and API errors with their extras`(): Unit = runBlocking {
        val token = linkToken(LinkPurpose.BACKUP)

        assertTrue(client.listBackups(token).containsKey("usage"))

        val invalid = assertThrows<PanoHostException> { runBlocking { client.listBackups("nope") } }
        assertEquals(PanoHostException.INVALID_TOKEN, invalid.code)
        assertEquals(401, invalid.status)

        val scope = assertThrows<PanoHostException> { runBlocking { client.listTransfers(token) } }
        assertEquals("NO_PERMISSION", scope.code)
        assertEquals("LINK_SCOPE", scope.extras.getString("reason"))

        host.subscription = false

        try {
            val payment = assertThrows<PanoHostException> { runBlocking { client.startBackup(token, 10, "pano-instance", null) } }
            assertEquals("PAYMENT_REQUIRED", payment.code)
            assertEquals("NO_SUBSCRIPTION", payment.extras.getString("reason"))
        } finally {
            host.subscription = true
        }

        val gone = assertThrows<PanoHostException> { runBlocking { client.linkPoll("unknown") } }
        assertEquals("LINK_NOT_FOUND", gone.code)

        val down = PanoHostClient({ "http://127.0.0.1:18479" }, RetryPolicy(1, 1))
        assertEquals(PanoHostException.UNAVAILABLE, assertThrows<PanoHostException> { runBlocking { down.linkStart("x", LinkPurpose.BACKUP) } }.code)
        assertEquals(PanoHostException.UNAVAILABLE, assertThrows<PanoHostException> { runBlocking { PanoHostClient({ "" }).linkStart("x", LinkPurpose.BACKUP) } }.code)
    }

    @Test
    fun `multipart upload streams file slices, retries 5xx, and a download is checked`(): Unit = runBlocking {
        val token = linkToken(LinkPurpose.BACKUP)
        val bytes = Random(1).nextBytes(4096 * 2 + 777)
        val file = File(temp, "archive.panoarc").apply { writeBytes(bytes) }
        val session = client.startBackup(token, bytes.size.toLong(), "pano-instance", null)

        assertEquals(3, session.parts.size)

        // Part 2 fails twice (500) and then goes through within the 3 attempts.
        host.failPuts["backups/${session.id}#2"] = 2

        val progress = mutableListOf<Long>()

        client.uploadParts(file, session) { progress.add(it) }
        assertEquals(listOf(4096L, 8192L, bytes.size.toLong()), progress)

        val done = client.completeBackup(token, session.id, sha256Hex(bytes), null)

        assertEquals("DONE", done.getString("status"))
        assertArrayEquals(bytes, host.store.read("backups/${session.id}"))

        val download = client.downloadBackup(token, session.id)
        val target = File(temp, "download.panoarc")

        client.download(download.getString("url"), target, download.getLong("sizeBytes"), download.getString("sha256"))
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(temp, "download.panoarc.part").exists())

        val bad = File(temp, "bad.panoarc")
        val integrity = assertThrows<PanoHostException> {
            runBlocking { client.download(download.getString("url"), bad, download.getLong("sizeBytes"), "0".repeat(64)) }
        }

        assertEquals(PanoHostException.INTEGRITY_FAILED, integrity.code)
        assertFalse(bad.exists())
        assertFalse(File(temp, "bad.panoarc.part").exists())

        client.deleteBackup(token, session.id)
        assertEquals(null, host.store.read("backups/${session.id}"))
    }

    @Test
    fun `an upload gives up after the retries and never retries a 403`(): Unit = runBlocking {
        val token = linkToken(LinkPurpose.BACKUP)
        val file = File(temp, "a.panoarc").apply { writeBytes(Random(2).nextBytes(100)) }

        val flaky = client.startBackup(token, 100, "mc-server", "s-1")
        host.failPuts["backups/${flaky.id}#1"] = 5
        assertEquals(PanoHostException.UPLOAD_FAILED, assertThrows<PanoHostException> { runBlocking { client.uploadParts(file, flaky) } }.code)
        assertEquals(2, host.failPuts["backups/${flaky.id}#1"])

        val expired = client.startBackup(token, 100, "mc-server", "s-2")
        host.failPuts["backups/${expired.id}#1"] = -1
        val error = assertThrows<PanoHostException> { runBlocking { client.uploadParts(file, expired) } }
        assertEquals(PanoHostException.UPLOAD_FAILED, error.code)
        assertEquals(403, error.status)

        // A session that does not fit the file is refused before anything is sent.
        val wrong = UploadSession("x", 10, listOf(UploadSession.Part(1, "${host.baseUrl}/s3/x")), 0)
        assertThrows<PanoHostException> { runBlocking { client.uploadParts(file, wrong) } }
    }

    @Test
    fun `slice reads exactly its range`() {
        val bytes = Random(3).nextBytes(1000)
        val file = File(temp, "slice.bin").apply { writeBytes(bytes) }

        PanoHostClient.slice(file, 100, 250).use { assertArrayEquals(bytes.copyOfRange(100, 350), it.readBytes()) }
        PanoHostClient.slice(file, 900, 100).use { assertArrayEquals(bytes.copyOfRange(900, 1000), it.readBytes()) }
        assertEquals(sha256Hex(bytes), PanoHostClient.sha256(file))
    }
}
