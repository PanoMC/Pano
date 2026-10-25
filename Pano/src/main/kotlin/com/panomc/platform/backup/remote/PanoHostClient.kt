package com.panomc.platform.backup.remote

import com.panomc.snapshot.SnapshotFormat
import com.panomc.snapshot.SnapshotRepoConfig
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
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * An error from the Pano Host API (`{"result":"error","error":"CODE",…}`) or from talking to it.
 * [code] is the API's code (`PAYMENT_REQUIRED`, `QUOTA_EXCEEDED`, `INVALID_TOKEN`, …) or one of the
 * client's own: [UNAVAILABLE], [UPLOAD_FAILED], [DOWNLOAD_FAILED], [INTEGRITY_FAILED],
 * [CONNECT_REQUIRED], [STOPPED_REMOTELY].
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

        /** This Pano is not connected to a panomc.com account (or the connection was revoked): connect it. */
        const val CONNECT_REQUIRED = "CONNECT_REQUIRED"

        /** The upload was stopped on the website (`/host/manage/backups`). */
        const val STOPPED_REMOTELY = "STOPPED_REMOTELY"
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

/** `POST /host/connected/snapshots` / `…/:id/open`: the sealing key of the Pano's repository and the manifest URL (PUT when starting, GET when opening). */
class SnapshotSession(val id: String, val keyId: String, val key: ByteArray, val repoConfig: SnapshotRepoConfig, val manifestUrl: String) {
    companion object {
        fun from(json: JsonObject, id: String, urlKey: String): SnapshotSession {
            fun bad(what: String): Nothing = throw PanoHostException(PanoHostException.UNAVAILABLE, message = "Pano Host answered a snapshot without $what.")

            val key = runCatching { Base64.getDecoder().decode(json.getString("key")) }.getOrNull()?.takeIf { it.size == 32 } ?: bad("a usable key")
            val config = runCatching { SnapshotFormat.parseConfig((json.getJsonObject("repoConfig") ?: bad("a repository config")).encode().toByteArray()).validate() }
                .getOrElse { throw PanoHostException(PanoHostException.UNAVAILABLE, message = "Pano Host answered an unsupported snapshot repository.", cause = it) }

            return SnapshotSession(
                id = json.getString("snapshotId") ?: id,
                keyId = json.getString("keyId")?.takeIf { it.isNotEmpty() } ?: bad("a key id"),
                key = key,
                repoConfig = config,
                manifestUrl = json.getJsonObject(urlKey)?.getString("url") ?: bad("a manifest URL")
            )
        }
    }
}

/** A pack upload slot: the pack's id (the sealed index is bound to it) and its presigned PUT. */
data class PackSlot(val id: String, val url: String)

/** Where one chunk sits: pack, offset and length of its sealed blob. */
data class ChunkLocation(val id: String, val pack: String, val offset: Long, val length: Int)

/** `…/:id/chunk-urls`: the chunks' places, a presigned GET per pack and the ids the repository does not know. */
class ChunkLocations(val chunks: Map<String, ChunkLocation>, val packs: Map<String, String>, val missing: List<String>)

/** The numbers `…/:id/complete` takes (`downloadBytes` = the size of the plain `.panoarc` of this snapshot). */
data class SnapshotStats(val fileCount: Long, val logicalBytes: Long, val downloadBytes: Long, val chunkCount: Long, val newChunks: Long, val newBytes: Long) {
    fun toJson(): JsonObject = JsonObject().put("fileCount", fileCount).put("logicalBytes", logicalBytes).put("downloadBytes", downloadBytes)
        .put("chunkCount", chunkCount).put("newChunks", newChunks).put("newBytes", newBytes)
}

/** Retries of one part PUT / download: network errors, 408, 429 and 5xx; [baseDelayMs] doubles each time. */
data class RetryPolicy(
    val attempts: Int = 4,
    val baseDelayMs: Long = 1000,
    /** A storage download that brings nothing for this long is broken off and retried. */
    val stallMs: Long = 60_000
)

/** Who this Pano is on `/host/connected/…` calls (`X-Pano-Instance-Id` / `X-Pano-Instance-Name`). */
data class PanoIdentity(val instanceId: String, val instanceName: String)

/**
 * Talks to the Pano Host control plane as a connected Pano (host-api.md §Connected Pano): the Pano
 * Backup (`/host/connected/backups/…`) and transfer (`/host/connected/workloads`,
 * `/host/connected/transfers/…`) routes, and the presigned S3 part uploads / downloads they hand out.
 *
 * [baseUrl] is read per call (e.g. `https://api.panomc.com`; the control plane's routes are
 * appended to it). [token] is the panomc.com platform connection JWT (`panoAccount.accessToken`,
 * null = not connected → [PanoHostException.CONNECT_REQUIRED]); [identity] names this Pano. The
 * account password never reaches this server and presigned URLs are never logged.
 */
class PanoHostClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val identity: suspend () -> PanoIdentity,
    private val retry: RetryPolicy = RetryPolicy(),
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
) {
    fun isConnected(): Boolean = !token().isNullOrBlank()

    /** `GET /host/connected` → `{accountId, platformId, username, instanceId, instanceName}`. */
    suspend fun connected(): JsonObject = call("GET", "/host/connected")

    // Pano Backup

    /** `{instanceId, tier, subscription, usage, panos[]}` of the connection's account. */
    suspend fun listBackups(timeout: Duration = Duration.ofSeconds(60)): JsonObject =
        call("GET", "/host/connected/backups", null, timeout)

    suspend fun startBackup(size: Long, kind: String, subject: String?): UploadSession {
        val body = JsonObject().put("size", size).put("kind", kind)

        subject?.let { body.put("subject", it) }

        return UploadSession.from(call("POST", "/host/connected/backups", body), "backupId")
    }

    /** `{backup}` → the backup (`status` `CANCELED` = stopped on the website). */
    suspend fun getBackup(backupId: String): JsonObject =
        call("GET", "/host/connected/backups/${segment(backupId)}").getJsonObject("backup") ?: JsonObject()

    suspend fun completeBackup(backupId: String, sha256: String, manifest: JsonObject?): JsonObject {
        val body = JsonObject().put("sha256", sha256)

        manifest?.let { body.put("manifest", it) }

        return call("POST", "/host/connected/backups/${segment(backupId)}/complete", body).getJsonObject("backup") ?: JsonObject()
    }

    /** `{url, expiresAt, sizeBytes, sha256}`. */
    suspend fun downloadBackup(backupId: String): JsonObject =
        call("POST", "/host/connected/backups/${segment(backupId)}/download", JsonObject())

    suspend fun deleteBackup(backupId: String) {
        call("DELETE", "/host/connected/backups/${segment(backupId)}")
    }

    // Snapshots (deduplicated, host-api.md "Snapshots of connected Panos")

    /** Opens a snapshot; `PAYMENT_REQUIRED`, `QUOTA_EXCEEDED` and `SNAPSHOT_BUSY` are thrown as [PanoHostException]s. */
    /** [subject]: the uuid of the managed server the snapshot is of; null = this Pano itself. */
    suspend fun startSnapshot(name: String?, subject: String? = null, trigger: String? = null): SnapshotSession {
        val data = call("POST", "/host/connected/snapshots", JsonObject().apply {
            name?.let { put("name", it) }
            subject?.let { put("subject", it) }
            trigger?.let { put("trigger", it) }
        })

        return SnapshotSession.from(data, data.getString("snapshotId") ?: "", "manifestPut")
    }

    /** The ids of [chunks] (id, raw size; at most [MAX_SNAPSHOT_BATCH]) the repository does not hold yet. */
    suspend fun snapshotMissing(snapshotId: String, chunks: List<Pair<String, Int>>): Set<String> {
        val body = JsonObject().put("chunks", JsonArray(chunks.map { JsonObject().put("id", it.first).put("size", it.second) }))

        return (call("POST", "/host/connected/snapshots/${segment(snapshotId)}/chunks", body).getJsonArray("missing") ?: JsonArray()).map { it as String }.toSet()
    }

    /** Reserves a pack of exactly [size] bytes (checked against the quota) and returns its id and PUT URL. */
    suspend fun startSnapshotPack(snapshotId: String, size: Long): PackSlot {
        val data = call("POST", "/host/connected/snapshots/${segment(snapshotId)}/packs", JsonObject().put("size", size))

        return PackSlot(
            data.getString("packId") ?: throw PanoHostException(PanoHostException.UNAVAILABLE, message = "No packId in the pack slot."),
            data.getString("url") ?: throw PanoHostException(PanoHostException.UNAVAILABLE, message = "No url in the pack slot.")
        )
    }

    /** Settles the snapshot (Pano Host reads the manifest and the pack indexes); returns its view, read `status` / `error`. */
    suspend fun completeSnapshot(snapshotId: String, stats: SnapshotStats): JsonObject =
        call("POST", "/host/connected/snapshots/${segment(snapshotId)}/complete", stats.toJson(), Duration.ofMinutes(10)).getJsonObject("snapshot") ?: JsonObject()

    /** Key + manifest URL of a finished snapshot of the account. */
    suspend fun openSnapshot(snapshotId: String): SnapshotSession =
        SnapshotSession.from(call("POST", "/host/connected/snapshots/${segment(snapshotId)}/open", JsonObject()), snapshotId, "manifestGet")

    /** Where [ids] (at most [MAX_SNAPSHOT_BATCH]) are stored. */
    suspend fun snapshotChunkUrls(snapshotId: String, ids: List<String>): ChunkLocations {
        val data = call("POST", "/host/connected/snapshots/${segment(snapshotId)}/chunk-urls", JsonObject().put("ids", JsonArray(ids)))
        val chunks = (data.getJsonArray("chunks") ?: JsonArray()).map { it as JsonObject }.associate {
            it.getString("id") to ChunkLocation(it.getString("id"), it.getString("pack"), it.getLong("offset"), it.getInteger("length"))
        }
        val packs = (data.getJsonArray("packs") ?: JsonArray()).map { it as JsonObject }.associate { it.getString("id") to it.getString("url") }

        return ChunkLocations(chunks, packs, (data.getJsonArray("missing") ?: JsonArray()).map { it as String })
    }

    /** `{snapshots[view], repository {storedBytes}}` of this Pano. */
    suspend fun listSnapshots(timeout: Duration = Duration.ofSeconds(60)): JsonObject = call("GET", "/host/connected/snapshots", null, timeout)

    /** Deletes one of this Pano's snapshots (an open one is aborted). */
    suspend fun deleteSnapshot(snapshotId: String) {
        call("DELETE", "/host/connected/snapshots/${segment(snapshotId)}")
    }

    // Moving out of Pano Host

    /** `{workloads[{id, name, label, state, exportable, reason?, export{availableAt, current?}}]}`: the account's instances. */
    suspend fun listExports(): JsonObject = call("GET", "/host/connected/exports")

    /** `{export{exportId, status, sizeBytes, expiresAt, url?}}`: the instance's valid export, or a new live one. */
    suspend fun startExport(workloadId: String): JsonObject =
        call("POST", "/host/connected/workloads/${segment(workloadId)}/export", JsonObject())

    /** `{export}` of [workloadId] (null when there is none); `url` once it is `DONE`. */
    suspend fun exportStatus(workloadId: String): JsonObject =
        call("GET", "/host/connected/workloads/${segment(workloadId)}/export")

    // Transfers

    /** `{workloads[{id, name, label, state, maxBytes}]}`: the account's Pano workloads a transfer may target. */
    suspend fun listWorkloads(): JsonObject = call("GET", "/host/connected/workloads")

    /** `{transfers}` pushed by this Pano. */
    suspend fun listTransfers(): JsonObject = call("GET", "/host/connected/transfers")

    /** E-mails a confirmation code to the account owner: `{email (masked), expiresAt, resendAfter}`; `TRANSFER_CODE_COOLDOWN` when asked too early. */
    suspend fun requestTransferCode(workloadId: String): JsonObject =
        call("POST", "/host/connected/transfers/code", JsonObject().put("workloadId", workloadId))

    /** Checks the e-mailed code: the single-use `grant` (valid 2 h) that starts a transfer; `TRANSFER_CODE_INVALID` otherwise. */
    suspend fun verifyTransferCode(workloadId: String, code: String): String =
        call("POST", "/host/connected/transfers/code/verify", JsonObject().put("workloadId", workloadId).put("code", code)).getString("grant")
            ?: throw PanoHostException(PanoHostException.UNAVAILABLE, message = "No grant in the answer.")

    suspend fun startTransfer(workloadId: String, size: Long, grant: String): UploadSession = UploadSession.from(
        call("POST", "/host/connected/transfers", JsonObject().put("workloadId", workloadId).put("size", size).put("grant", grant)),
        "transferId"
    )

    suspend fun completeTransfer(transferId: String): JsonObject =
        call("POST", "/host/connected/transfers/${segment(transferId)}/complete", JsonObject()).getJsonObject("transfer") ?: JsonObject()

    suspend fun getTransfer(transferId: String): JsonObject =
        call("GET", "/host/connected/transfers/${segment(transferId)}").getJsonObject("transfer") ?: JsonObject()

    suspend fun cancelTransfer(transferId: String) {
        call("DELETE", "/host/connected/transfers/${segment(transferId)}")
    }

    // Pano Mail

    /** `{enabled, ready, from, plan, usage, senders, limits}` of the connection's account. */
    suspend fun mailStatus(timeout: Duration = Duration.ofSeconds(60)): JsonObject =
        call("GET", "/host/connected/mail", null, timeout)

    /**
     * Idempotent: switches Pano Mail on for this Pano; same body as [mailStatus]. [name] creates the
     * account's sender address with that name, or renames the existing one (`LABEL_INVALID`,
     * `LABEL_TAKEN`, `RATE_LIMITED`).
     */
    suspend fun enableMail(name: String? = null): JsonObject =
        call("POST", "/host/connected/mail/enable", name?.let { JsonObject().put("name", it) })

    /** `{id, recipients}`; [mime] is the raw MIME message (sent base64). */
    suspend fun sendMail(from: String, to: List<String>, mime: ByteArray): JsonObject =
        call(
            "POST", "/host/connected/mail/send",
            JsonObject().put("from", from).put("to", JsonArray(to)).put("mime", java.util.Base64.getEncoder().encodeToString(mime))
        )

    /** `{name, status, reason?, minLength?, maxLength?}`: whether the account may use [name] as its sender address name. */
    suspend fun mailNameCheck(name: String, timeout: Duration = Duration.ofSeconds(5)): JsonObject =
        call("POST", "/host/connected/mail/name-check", JsonObject().put("name", name), timeout)

    /** `GET /host/catalog` (public, no token): `{mailPlans[…], …}`; [timeout] bounds the whole call. */
    suspend fun catalog(timeout: Duration = Duration.ofSeconds(5)): JsonObject = send("GET", "/host/catalog", null, null, null, timeout)

    // Object storage (presigned URLs)

    /**
     * PUTs [file] into [session]'s parts (part n = bytes `(n-1)·partSize` until the next part), one
     * after the other, each streamed from disk and retried per [retry]. [onProgress] gets the bytes
     * sent so far after every finished part; [beforePart] runs before each part (the caller checks
     * there whether the upload was stopped on the website).
     */
    suspend fun uploadParts(
        file: File,
        session: UploadSession,
        beforePart: suspend (partNumber: Int) -> Unit = {},
        onProgress: (Long) -> Unit = {}
    ) {
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

            beforePart(part.partNumber)
            withRetry(PanoHostException.UPLOAD_FAILED) { putPart(file, offset, length, part.url) }

            offset += length
            onProgress(offset)
        }

        if (offset != size) {
            throw PanoHostException(PanoHostException.UPLOAD_FAILED, message = "The upload session does not fit the archive.")
        }
    }

    /** PUTs all of [file] to the presigned [url] (the signature covers its exact length), retried per [retry]. */
    suspend fun putFile(url: String, file: File) {
        val length = file.length()

        withRetry(PanoHostException.UPLOAD_FAILED) { putPart(file, 0, length, url) }
    }

    /** PUTs [bytes] to the presigned [url], retried per [retry]. */
    suspend fun putBytes(url: String, bytes: ByteArray) {
        withRetry(PanoHostException.UPLOAD_FAILED) {
            withContext(Dispatchers.IO) {
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()

                checkStorageStatus(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(), "upload")
            }
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

        // The request timeout ends once the headers are in: a body that stops coming would be waited for forever. A read
        // that brings nothing for [RetryPolicy.stallMs] is broken off (the reading thread is interrupted) and the download retried.
        val reader = Thread.currentThread()
        val stallCheckMs = (retry.stallMs / 4).coerceIn(10L, 5_000L)
        val lastRead = AtomicLong(System.nanoTime())
        val stalled = AtomicBoolean(false)
        val watchdog = STALL_WATCH.scheduleAtFixedRate({
            if (System.nanoTime() - lastRead.get() > TimeUnit.MILLISECONDS.toNanos(retry.stallMs) && stalled.compareAndSet(false, true)) {
                reader.interrupt()
            }
        }, stallCheckMs, stallCheckMs, TimeUnit.MILLISECONDS)

        try {
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

                        lastRead.set(System.nanoTime())
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
        } catch (e: IOException) {
            if (stalled.get()) throw RetryableException("The download stopped coming for ${retry.stallMs / 1000} s.", e)

            throw e
        } finally {
            watchdog.cancel(false)
            // The interrupt was for the read; the pool thread must not keep it.
            if (stalled.get()) Thread.interrupted()
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
    private suspend fun call(method: String, path: String, body: JsonObject? = null, timeout: Duration = Duration.ofSeconds(60)): JsonObject {
        val token = token()?.takeIf { it.isNotBlank() }
            ?: throw PanoHostException(PanoHostException.CONNECT_REQUIRED, extras = JsonObject().put("reason", "NOT_CONNECTED"))
        val identity = identity()

        return send(method, path, token, identity, body, timeout)
    }

    private suspend fun send(
        method: String,
        path: String,
        token: String?,
        identity: PanoIdentity?,
        body: JsonObject?,
        timeout: Duration = Duration.ofSeconds(60)
    ): JsonObject = withContext(Dispatchers.IO) {
        val base = baseUrl().trim().trimEnd('/')

        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw PanoHostException(PanoHostException.UNAVAILABLE, message = "The Pano Host API URL is not set.")
        }

        val builder = HttpRequest.newBuilder(URI(base + path))
            .timeout(timeout)
            .header("Accept", "application/json")

        if (token != null && identity != null) {
            builder.header("Authorization", "Bearer $token")
                .header(HEADER_INSTANCE_ID, identity.instanceId)
                .header(HEADER_INSTANCE_NAME, headerSafe(identity.instanceName))
        }

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

        // The connection is gone on panomc.com (revoked, reconnected elsewhere, deleted): reconnect.
        if (code == PanoHostException.INVALID_TOKEN || (code == NOT_CONNECTED && extras.getString("reason") == "MISSING")) {
            throw PanoHostException(PanoHostException.CONNECT_REQUIRED, response.statusCode(), extras.put("reason", code))
        }

        throw PanoHostException(code, response.statusCode(), extras)
    }

    companion object {
        private const val PROGRESS_STEP = 8L * 1024 * 1024

        private val STALL_WATCH: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "pano-backup-download-watch").apply { isDaemon = true }
        }

        /** Chunk ids per `chunks` / `chunk-urls` call. */
        const val MAX_SNAPSHOT_BATCH = 1000

        const val HEADER_INSTANCE_ID = "X-Pano-Instance-Id"
        const val HEADER_INSTANCE_NAME = "X-Pano-Instance-Name"
        private const val NOT_CONNECTED = "NOT_CONNECTED"

        /**
         * A header value is ASCII: accents are stripped (`Sunucum Ğüş` → `Sunucum Gus`, `ı` → `i`), anything
         * else non-printable becomes a space; whitespace runs collapse, ≤ 64 chars, never empty.
         */
        fun headerSafe(name: String): String =
            java.text.Normalizer.normalize(name.replace('ı', 'i').replace('İ', 'I'), java.text.Normalizer.Form.NFD)
                .filter { it !in '\u0300'..'\u036f' }
                .map { if (it in ' '..'~') it else ' ' }.joinToString("")
                .replace(Regex(" +"), " ").trim().take(64).trim().ifEmpty { "Pano" }

        /** A new random instance id (`[A-Za-z0-9-]{8,64}`). */
        fun newInstanceId(): String = java.util.UUID.randomUUID().toString()

        fun isValidInstanceId(id: String?): Boolean = id != null && id.length in 8..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

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
