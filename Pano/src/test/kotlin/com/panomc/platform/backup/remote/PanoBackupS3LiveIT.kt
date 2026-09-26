package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.backup.PanoBackupJob
import io.vertx.core.Vertx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Pano Backup client against the REAL bucket: a fake control plane on 127.0.0.1:18473 hands out
 * presigned multipart part URLs / GET URLs exactly like pano-host does (SigV4 query presign, complete
 * from ListParts), so the upload + download legs run against Hetzner S3. Everything lives under
 * `test/w6/<uuid>/` and is deleted afterwards.
 *
 * Gated: `PANO_IT_S3=1` + `HETZNER_S3_ENDPOINT/REGION/BUCKET_HOST/ACCESS_KEY/SECRET_KEY` (from
 * `.secrets/pano-host.env`).
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_S3", matches = "1")
class PanoBackupS3LiveIT {
    @TempDir
    lateinit var temp: File

    /** A minimal S3 client for the fake control plane side (presign + header-signed calls). */
    class S3Store(private val prefix: String) : ArchiveStore {
        private val env = System.getenv()
        private val credentials = TestSigV4.Credentials(env.getValue("HETZNER_S3_ACCESS_KEY"), env.getValue("HETZNER_S3_SECRET_KEY"))
        private val region = env.getValue("HETZNER_S3_REGION")
        private val bucket = env.getValue("HETZNER_S3_BUCKET_HOST")
        private val endpoint = URI(env.getValue("HETZNER_S3_ENDPOINT").trimEnd('/'))
        private val host = "$bucket.${endpoint.host}"
        private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        private val uploads = ConcurrentHashMap<String, Pair<String, Int>>()
        val keys = ConcurrentHashMap.newKeySet<String>()

        private fun path(key: String) = "/$prefix$key"

        private fun url(path: String, query: String) = "${endpoint.scheme}://$host${TestSigV4.canonicalUri(path)}" + if (query.isEmpty()) "" else "?$query"

        private fun presign(method: String, key: String, query: Map<String, String> = emptyMap()): String =
            url(path(key), TestSigV4.presignQuery(credentials, method, path(key), query, mapOf("host" to host), region, System.currentTimeMillis(), 3600))

        private fun signed(method: String, key: String, query: Map<String, String>, body: ByteArray = ByteArray(0)): HttpResponse<String> {
            val payloadHash = TestSigV4.sha256Hex(body)
            val amzDate = TestSigV4.amzDate(System.currentTimeMillis())
            val headers = mapOf("host" to host, "x-amz-date" to amzDate, "x-amz-content-sha256" to payloadHash)
            val authorization = TestSigV4.authorization(credentials, method, path(key), query, headers, payloadHash, region)
            val request = HttpRequest.newBuilder(URI(url(path(key), TestSigV4.canonicalQuery(query))))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", authorization)
                .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
                .build()

            return http.send(request, HttpResponse.BodyHandlers.ofString()).also {
                check(it.statusCode() in 200..299) { "S3 $method ${it.statusCode()}: ${it.body().take(300)}" }
            }
        }

        override fun open(key: String, size: Long): Pair<Long, List<String>> {
            val partSize = 5L shl 20
            val count = maxOf(1L, (size + partSize - 1) / partSize).toInt()
            val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(signed("POST", key, mapOf("uploads" to "")).body())!!.groupValues[1]

            keys.add(key)
            uploads[key] = uploadId to count

            return partSize to (1..count).map { presign("PUT", key, mapOf("partNumber" to it.toString(), "uploadId" to uploadId)) }
        }

        override fun complete(key: String): Long? {
            val (uploadId, count) = uploads[key] ?: return null
            val listed = signed("GET", key, mapOf("uploadId" to uploadId)).body()
            val parts = Regex("<Part>(.*?)</Part>", RegexOption.DOT_MATCHES_ALL).findAll(listed).map { match ->
                val part = match.groupValues[1]

                Triple(
                    Regex("<PartNumber>(\\d+)</PartNumber>").find(part)!!.groupValues[1].toInt(),
                    Regex("<ETag>([^<]+)</ETag>").find(part)!!.groupValues[1].replace("&quot;", "\""),
                    Regex("<Size>(\\d+)</Size>").find(part)!!.groupValues[1].toLong()
                )
            }.sortedBy { it.first }.toList()

            if (parts.size != count) return null

            val xml = "<CompleteMultipartUpload>" + parts.joinToString("") { "<Part><PartNumber>${it.first}</PartNumber><ETag>${it.second}</ETag></Part>" } +
                    "</CompleteMultipartUpload>"

            signed("POST", key, mapOf("uploadId" to uploadId), xml.toByteArray())
            uploads.remove(key)

            return parts.sumOf { it.third }
        }

        override fun downloadUrl(key: String) = presign("GET", key)

        override fun read(key: String): ByteArray? =
            http.send(HttpRequest.newBuilder(URI(downloadUrl(key))).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .takeIf { it.statusCode() == 200 }?.body()

        override fun delete(key: String) {
            uploads.remove(key)?.let { (uploadId, _) -> runCatching { signed("DELETE", key, mapOf("uploadId" to uploadId)) } }
            signed("DELETE", key, emptyMap())
        }

        fun cleanup() {
            keys.forEach { key -> runCatching { delete(key) } }
        }
    }

    @Test
    fun `upload to and restore from the real bucket through presigned URLs`(): Unit = runBlocking {
        val vertx = Vertx.vertx()
        val store = S3Store("test/w6/${UUID.randomUUID()}/")
        val host = FakePanoHost(vertx, 18473) { store }.start()
        val runner = FakeRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default), payloadBytes = 6 * 1024 * 1024)
        val passphrase = "live round trip passphrase"
        val service = PanoRemoteBackupService(
            client = PanoHostClient({ host.baseUrl }),
            stateStore = MemoryRemoteStateStore(),
            passphraseFile = PassphraseFile(File(temp, PassphraseFile.FILE_NAME)),
            backups = runner,
            tempDir = { File(temp, ".temp") },
            instanceName = { "W6 live IT" },
            panoVersion = "1.0.0-test"
        )

        try {
            service.startLink(LinkPurpose.BACKUP)
            host.approved[LinkPurpose.BACKUP] = true
            assertEquals(PanoRemoteBackupService.LINKED, service.pollLink(LinkPurpose.BACKUP).getString("status"))
            service.setPassphrase(passphrase.toCharArray())

            val upload = awaitJob(service.startUpload(), 300_000)

            assertEquals(PanoBackupJob.Status.DONE, upload.status, "${upload.error} ${upload.message}")

            val backup = host.backups.getValue(upload.remoteId!!)

            assertTrue(backup.getLong("sizeBytes") > 5L shl 20, "two parts")

            val restore = awaitJob(service.startRestore(upload.remoteId!!, null), 300_000)

            assertEquals(PanoBackupJob.Status.DONE, restore.status, "${restore.error} ${restore.message}")
            assertEquals(ArchiveManifest.KIND_PANO_INSTANCE, runner.restored.single().kind)

            service.deleteBackup(upload.remoteId!!)
            assertNull(store.read("backups/${upload.remoteId}"))
        } finally {
            store.cleanup()
            host.stop()
            vertx.close()
        }
    }
}
