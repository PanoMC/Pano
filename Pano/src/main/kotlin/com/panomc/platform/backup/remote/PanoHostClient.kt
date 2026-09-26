package com.panomc.platform.backup.remote

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * An error from the Pano Host API (`{"result":"error","error":"CODE",…}`) or from talking to it.
 * [code] is the API's code (`PAYMENT_REQUIRED`, `QUOTA_EXCEEDED`, `INVALID_TOKEN`, …) or one of the
 * client's own: [UNAVAILABLE], [UPLOAD_FAILED], [DOWNLOAD_FAILED], [INTEGRITY_FAILED].
 */
class PanoHostException(
    val code: String,
    val status: Int = 0,
    val extras: JsonObject = JsonObject(),
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: code, cause) {
    companion object {
        const val UNAVAILABLE = "PANO_HOST_UNAVAILABLE"
        const val UPLOAD_FAILED = "UPLOAD_FAILED"
        const val DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
        const val INTEGRITY_FAILED = "INTEGRITY_FAILED"
        const val INVALID_TOKEN = "INVALID_TOKEN"
    }
}

/** A presigned multipart upload session (`{<id>, partSize, parts[{partNumber, url}], expiresAt}`). */
data class UploadSession(val id: String, val partSize: Long, val parts: List<Part>, val expiresAt: Long) {
    data class Part(val partNumber: Int, val url: String)

    companion object {
        fun from(json: JsonObject, idKey: String) = UploadSession(
            id = json.getString(idKey) ?: throw PanoHostException(PanoHostException.UNAVAILABLE, message = "No $idKey in the upload session."),
            partSize = json.getLong("partSize", 0L),
            parts = (json.getJsonArray("parts") ?: JsonArray()).map { part ->
                part as JsonObject
                Part(part.getInteger("partNumber"), part.getString("url"))
            }.sortedBy { it.partNumber },
            expiresAt = json.getLong("expiresAt", 0L)
        )
    }
}

/** Retries of one part PUT / download: network errors, 408, 429 and 5xx; [baseDelayMs] doubles each time. */
data class RetryPolicy(val attempts: Int = 4, val baseDelayMs: Long = 1000)

/**
 * Talks to the Pano Host control plane as a self-hosted Pano (host-api.md §Link): the device-code
 * link flow, the link-scoped Pano Backup (`/host/link/backups/…`) and transfer
 * (`/host/link/transfers/…`) routes, and the presigned S3 part uploads / downloads they hand out.
 *
 * [baseUrl] is read per call (e.g. `https://api.panomc.com`; the control plane's routes are
 * appended to it). The panomc.com account password never reaches this server: a link token is the
 * only credential, and presigned URLs are never logged.
 */
class PanoHostClient(
    private val baseUrl: () -> String,
    private val retry: RetryPolicy = RetryPolicy(),
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
) {
    // Link flow

    /** `POST /host/link/start` → `{code, verifyUrl, pollToken, expiresIn, interval}`. */
    suspend fun linkStart(instanceName: String, purpose: LinkPurpose): JsonObject =
        call("POST", "/host/link/start", null, JsonObject().put("instanceName", instanceName).put("purpose", purpose.name))

    /** `POST /host/link/poll` → `{status: PENDING}` or once `{status: ACTIVE, token, linkId, purpose, workloadId}`. */
    suspend fun linkPoll(pollToken: String): JsonObject = call("POST", "/host/link/poll", null, JsonObject().put("pollToken", pollToken))

    /** `GET /host/link` → `{linkId, purpose, instanceName, workloadId}`. */
    suspend fun linkInfo(token: String): JsonObject = call("GET", "/host/link", token)

    // Pano Backup (BACKUP links)

    /** `{backups, tier, usage}`. */
    suspend fun listBackups(token: String): JsonObject = call("GET", "/host/link/backups", token)

    suspend fun startBackup(token: String, size: Long, kind: String, subject: String?): UploadSession {
        val body = JsonObject().put("size", size).put("kind", kind)

        subject?.let { body.put("subject", it) }

        return UploadSession.from(call("POST", "/host/link/backups", token, body), "backupId")
    }

    suspend fun completeBackup(token: String, backupId: String, sha256: String, manifest: JsonObject?): JsonObject {
        val body = JsonObject().put("sha256", sha256)

        manifest?.let { body.put("manifest", it) }

        return call("POST", "/host/link/backups/${segment(backupId)}/complete", token, body).getJsonObject("backup") ?: JsonObject()
    }

    /** `{url, expiresAt, sizeBytes, sha256}`. */
    suspend fun downloadBackup(token: String, backupId: String): JsonObject =
        call("POST", "/host/link/backups/${segment(backupId)}/download", token, JsonObject())

    suspend fun deleteBackup(token: String, backupId: String) {
        call("DELETE", "/host/link/backups/${segment(backupId)}", token)
    }

    // Transfers (TRANSFER links)

    /** `{workload, transfers}`. */
    suspend fun listTransfers(token: String): JsonObject = call("GET", "/host/link/transfers", token)

    suspend fun startTransfer(token: String, size: Long): UploadSession =
        UploadSession.from(call("POST", "/host/link/transfers", token, JsonObject().put("size", size)), "transferId")

    suspend fun completeTransfer(token: String, transferId: String): JsonObject =
        call("POST", "/host/link/transfers/${segment(transferId)}/complete", token, JsonObject()).getJsonObject("transfer") ?: JsonObject()

    suspend fun getTransfer(token: String, transferId: String): JsonObject =
        call("GET", "/host/link/transfers/${segment(transferId)}", token).getJsonObject("transfer") ?: JsonObject()

    suspend fun cancelTransfer(token: String, transferId: String) {
        call("DELETE", "/host/link/transfers/${segment(transferId)}", token)
    }

    // Object storage (presigned URLs)

    /**
     * PUTs [file] into [session]'s parts (part n = bytes `(n-1)·partSize` until the next part), one
     * after the other, each streamed from disk and retried per [retry]. [onProgress] gets the bytes
     * sent so far after every finished part.
     */
    suspend fun uploadParts(file: File, session: UploadSession, onProgress: (Long) -> Unit = {}) {
        val size = file.length()

        val expectedParts = maxOf(1L, (size + session.partSize - 1) / maxOf(1L, session.partSize))

        if (session.partSize <= 0 || session.parts.size.toLong() != expectedParts) {
            throw PanoHostException(PanoHostException.UPLOAD_FAILED, message = "The upload session does not fit the archive.")
        }

        var offset = 0L

        session.parts.forEachIndexed { index, part ->
            val last = index == session.parts.size - 1
            val length = if (last) size - offset else minOf(session.partSize, size - offset)

            if (length < 0 || (length == 0L && size > 0)) {
                throw PanoHostException(PanoHostException.UPLOAD_FAILED, message = "The upload session does not fit the archive.")
            }

            withRetry(PanoHostException.UPLOAD_FAILED) { putPart(file, offset, length, part.url) }

            offset += length
            onProgress(offset)
        }

        if (offset != size) {
            throw PanoHostException(PanoHostException.UPLOAD_FAILED, message = "The upload session does not fit the archive.")
        }
    }

    /**
     * Downloads [url] into [target] (via a `.part` file), retried per [retry]; checks the size and
     * the sha256 when given ([PanoHostException.INTEGRITY_FAILED]).
     */
    suspend fun download(url: String, target: File, expectedSize: Long?, expectedSha256: String?, onProgress: (Long) -> Unit = {}) {
        val part = File(target.parentFile, target.name + ".part")

        try {
            withRetry(PanoHostException.DOWNLOAD_FAILED) { get(url, part, onProgress) }

            if (expectedSize != null && part.length() != expectedSize) {
                throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "The download has the wrong size.")
            }

            if (expectedSha256 != null && !withContext(Dispatchers.IO) { sha256(part) }.equals(expectedSha256, ignoreCase = true)) {
                throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "The download has the wrong checksum.")
            }

            withContext(Dispatchers.IO) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            withContext(Dispatchers.IO) { part.delete() }
        }
    }

    private class RetryableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private suspend fun withRetry(code: String, block: suspend () -> Unit) {
        var attempt = 0

        while (true) {
            attempt++

            try {
                block()

                return
            } catch (e: RetryableException) {
                if (attempt >= retry.attempts) {
                    throw PanoHostException(code, message = e.message, cause = e)
                }
            } catch (e: IOException) {
                if (attempt >= retry.attempts) {
                    throw PanoHostException(code, message = e.message, cause = e)
                }
            }

            delay(retry.baseDelayMs shl (attempt - 1).coerceAtMost(10))
        }
    }

    private suspend fun putPart(file: File, offset: Long, length: Long, url: String) = withContext(Dispatchers.IO) {
        val publisher = HttpRequest.BodyPublishers.fromPublisher(
            HttpRequest.BodyPublishers.ofInputStream { slice(file, offset, length) },
            length
        )
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMinutes(30))
            .PUT(if (length == 0L) HttpRequest.BodyPublishers.noBody() else publisher)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())

        checkStorageStatus(response.statusCode(), "upload")
    }

    private suspend fun get(url: String, target: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())

        response.body().use { input ->
            if (response.statusCode() !in 200..299) {
                checkStorageStatus(response.statusCode(), "download")
            }

            target.outputStream().buffered(256 * 1024).use { output ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                var lastReport = 0L

                while (true) {
                    val read = input.read(buffer)

                    if (read < 0) break

                    output.write(buffer, 0, read)
                    total += read

                    if (total - lastReport >= PROGRESS_STEP) {
                        lastReport = total
                        onProgress(total)
                    }
                }

                onProgress(total)
            }
        }
    }

    private fun checkStorageStatus(status: Int, what: String) {
        when {
            status in 200..299 -> return
            status == 408 || status == 429 || status >= 500 -> throw RetryableException("The storage $what failed with HTTP $status.")
            else -> throw PanoHostException(
                if (what == "upload") PanoHostException.UPLOAD_FAILED else PanoHostException.DOWNLOAD_FAILED,
                status,
                message = "The storage $what failed with HTTP $status (the link may have expired)."
            )
        }
    }

    /** One control-plane call; returns `data` of an ok response, throws [PanoHostException] otherwise. */
    private suspend fun call(method: String, path: String, token: String?, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val base = baseUrl().trim().trimEnd('/')

        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw PanoHostException(PanoHostException.UNAVAILABLE, message = "The Pano Host API URL is not set.")
        }

        val builder = HttpRequest.newBuilder(URI(base + path))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")

        token?.let { builder.header("Authorization", "Bearer $it") }

        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.encode()))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw PanoHostException(PanoHostException.UNAVAILABLE, message = "Pano Host could not be reached: ${e.message}", cause = e)
        }

        val json = try {
            JsonObject(response.body())
        } catch (_: Exception) {
            throw PanoHostException(PanoHostException.UNAVAILABLE, response.statusCode(), message = "Pano Host answered HTTP ${response.statusCode()}.")
        }

        if (json.getString("result") == "ok") {
            return@withContext when (val data = json.getValue("data")) {
                is JsonObject -> data
                null -> JsonObject()
                else -> JsonObject().put("value", data)
            }
        }

        val code = json.getString("error")?.takeIf { it.isNotBlank() } ?: PanoHostException.UNAVAILABLE
        val extras = json.copy().apply { remove("result"); remove("error") }

        throw PanoHostException(code, response.statusCode(), extras)
    }

    companion object {
        private const val PROGRESS_STEP = 8L * 1024 * 1024

        private fun segment(id: String): String {
            require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid id." }

            return id
        }

        /** An input stream over `[offset, offset + length)` of [file]. */
        fun slice(file: File, offset: Long, length: Long): InputStream {
            val raf = RandomAccessFile(file, "r")

            raf.seek(offset)

            val channel = Channels.newInputStream(raf.channel)

            return object : FilterInputStream(channel) {
                private var remaining = length

                override fun read(): Int {
                    if (remaining <= 0) return -1

                    return super.read().also { if (it >= 0) remaining-- }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1

                    val read = super.read(b, off, minOf(len.toLong(), remaining).toInt())

                    if (read > 0) remaining -= read

                    return read
                }

                override fun close() {
                    super.close()
                    raf.close()
                }
            }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")

            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)

                while (true) {
                    val read = input.read(buffer)

                    if (read < 0) break

                    digest.update(buffer, 0, read)
                }
            }

            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

enum class LinkPurpose { BACKUP, TRANSFER }
