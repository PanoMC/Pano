package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.files.PanoBackupUploader
import com.panomc.node.net.BackupUploadMessage
import com.panomc.node.util.NodeLogger
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.backup.remote.McServerArchive
import com.panomc.snapshot.SnapshotFormat
import com.panomc.snapshot.SnapshotRepoConfig
import com.sun.net.httpserver.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * A backup sent to Pano Backup straight from the node (protocol 8), against a local stand-in for Pano's ticket
 * endpoints and for the storage: the archive goes up in parts and opens with the passphrase Pano derived its key from;
 * a snapshot asks Pano which chunks are missing, reserves its packs there and PUTs packs and manifest to the storage.
 */
class PanoBackupUploaderTest {
    @TempDir
    lateinit var data: File

    @TempDir
    lateinit var work: File

    private lateinit var server: HttpServer
    private val stored = ConcurrentHashMap<String, ByteArray>()
    private val calls = ConcurrentHashMap<String, MutableList<JsonObject>>()
    private var base = ""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"

        server.createContext("/api/v1/node/pano-backup/") { exchange ->
            val action = exchange.requestURI.path.substringAfterLast('/')
            val ticket = exchange.requestURI.path.removePrefix("/api/v1/node/pano-backup/").substringBefore('/')
            val body = JsonObject(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
            val answer = when {
                ticket != TICKET || exchange.requestHeaders.getFirst("Authorization") != "Bearer node-token" -> null
                action == "progress" -> JsonObject()
                action == "archive" -> {
                    val size = body.getLong("size")
                    val parts = JsonArray()

                    (1..((size + PART - 1) / PART).toInt()).forEach { parts.add(JsonObject().put("partNumber", it).put("url", "$base/store/part-$it")) }

                    JsonObject().put("partSize", PART).put("parts", parts)
                }

                action == "missing" -> JsonObject().put("missing", JsonArray(body.getJsonArray("chunks").map { (it as JsonObject).getString("id") }))
                action == "pack" -> {
                    val id = "%032x".format(calls[action]?.size ?: 0)

                    JsonObject().put("packId", id).put("url", "$base/store/pack-$id")
                }

                else -> null
            }

            calls.computeIfAbsent(action) { java.util.Collections.synchronizedList(ArrayList()) }.add(body)

            val bytes = (answer ?: JsonObject().put("error", JsonObject().put("code", "NOT_EXISTS"))).encode().toByteArray()

            exchange.sendResponseHeaders(if (answer == null) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/store/") { exchange ->
            stored[exchange.requestURI.path.removePrefix("/store/")] = exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun uploader(zip: File) = PanoBackupUploader(
        data,
        NodeConfig(platformUrl = base, token = "node-token"),
        { _, _, _ -> zip },
        NodeLogger("test", PrintStream(ByteArrayOutputStream()))
    )

    private fun serverZip(): File {
        val file = File(work, "backup.zip")

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("world/level.dat"))
            zip.write(Random(1).nextBytes(20_000))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("server.properties"))
            zip.write("motd=hello".toByteArray())
            zip.closeEntry()
        }

        return file
    }

    @Test
    fun `an encrypted archive goes up in parts and opens with the passphrase, which never reached the node`() {
        val zip = serverZip()
        val derived = PanoArcEncryption.derive("correct horse".toCharArray(), t = 1, mKiB = 8192, p = 1)
        val reply = uploader(zip).upload(
            BackupUploadMessage(
                serverUuid = "server-1", backupId = "backup-1", ticket = TICKET, mode = PanoBackupUploader.MODE_ARCHIVE,
                meta = JsonObject().put("name", "Nightly").encode(), producer = "test", instanceName = "My Pano",
                encryptionKey = Base64.getEncoder().encodeToString(derived.masterKey),
                kdfSalt = Base64.getEncoder().encodeToString(derived.kdf.salt), kdfT = 1, kdfMKiB = 8192, kdfP = 1
            )
        )

        assertTrue(reply.getBoolean("ok"), reply.encode())

        val parts = stored.keys.filter { it.startsWith("part-") }.sortedBy { it.removePrefix("part-").toInt() }
        val archive = parts.map { stored.getValue(it) }.reduce { a, b -> a + b }

        assertTrue(parts.size > 1, "more than one part")
        assertEquals(archive.size.toLong(), reply.getLong("size"))
        assertEquals(PanoBackupUploader.sha256(File(work, "a.panoarc").apply { writeBytes(archive) }), reply.getString("sha256"))
        assertEquals(reply.getLong("size"), calls.getValue("archive").single().getLong("size"))

        val opened = File(work, "opened")

        PanoArchive.extract(archive.inputStream(), PanoArcKeys(passphrase = "correct horse".toCharArray()), opened)

        assertArrayEquals(zip.readBytes(), File(opened, McServerArchive.ZIP_ENTRY).readBytes())
        assertEquals("Nightly", JsonObject(File(opened, McServerArchive.META_ENTRY).readText()).getString("name"))
        assertTrue(calls.getValue("progress").isNotEmpty(), "progress told to Pano")
        assertFalse(File(data, PanoBackupUploader.SCRATCH_DIRECTORY).listFiles().orEmpty().any(), "scratch removed")
    }

    @Test
    fun `a snapshot asks Pano for the missing chunks and its packs, and PUTs packs and manifest to the storage`() {
        val zip = serverZip()
        val config = SnapshotRepoConfig(version = SnapshotFormat.PACK_FORMAT_VERSION, pack = 1024L * 1024)
        val reply = uploader(zip).upload(
            BackupUploadMessage(
                serverUuid = "server-1", backupId = "backup-1", ticket = TICKET, mode = PanoBackupUploader.MODE_SNAPSHOT,
                meta = JsonObject().put("name", "Hourly").put("mode", "SNAPSHOT").encode(), producer = "test", instanceName = "My Pano",
                snapshotId = "snap-1", snapshotKeyId = "1", snapshotKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
                repoConfig = String(SnapshotFormat.configJson(config), Charsets.UTF_8), manifestUrl = "$base/store/manifest",
                snapshotName = "Hourly", createdAt = 1L
            )
        )

        assertTrue(reply.getBoolean("ok"), reply.encode())

        val stats = reply.getJsonObject("stats")

        assertEquals(2L + 1L, stats.getLong("fileCount"), "the two files and the meta")
        assertTrue(stats.getLong("newChunks") > 0)
        assertTrue(calls.getValue("missing").all { it.getString("snapshotId") == "snap-1" })
        assertTrue(stored.containsKey("manifest"))
        assertTrue(stored.keys.any { it.startsWith("pack-") })
    }

    @Test
    fun `a ticket Pano does not know fails the upload and leaves nothing behind`() {
        val reply = uploader(serverZip()).upload(
            BackupUploadMessage(
                serverUuid = "server-1", backupId = "backup-1", ticket = "other-ticket", mode = PanoBackupUploader.MODE_ARCHIVE,
                meta = "{}", producer = "test", instanceName = null
            )
        )

        assertFalse(reply.getBoolean("ok"))
        assertTrue(stored.isEmpty())
        assertFalse(File(data, PanoBackupUploader.SCRATCH_DIRECTORY).listFiles().orEmpty().any())
    }

    companion object {
        private const val TICKET = "ticket-1"
        private const val PART = 8 * 1024L
    }
}
