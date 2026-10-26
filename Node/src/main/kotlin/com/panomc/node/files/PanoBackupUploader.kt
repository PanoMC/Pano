package com.panomc.node.files

import com.panomc.node.config.NodeConfig
import com.panomc.node.net.BackupUploadMessage
import com.panomc.node.net.NodePaths
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcKdf
import com.panomc.platform.backup.remote.McServerArchive
import com.panomc.platform.backup.remote.McServerSnapshotUpload
import com.panomc.platform.backup.remote.PackSlot
import com.panomc.platform.backup.remote.SnapshotRemote
import com.panomc.platform.backup.remote.SnapshotSession
import com.panomc.snapshot.SnapshotFormat
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
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
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * Sends one backup to Pano Backup straight from this node (`BACKUP_UPLOAD`, protocol 8), so the bytes never travel
 * through Pano: the `.panoarc` is written here (sealed with the key Pano derived for this one archive) or the snapshot is
 * cut here, and only what the control plane must answer -- the part URLs of an archive, which chunks the repository
 * lacks, a pack slot -- is asked of Pano under the upload's ticket. Progress goes to Pano the same way, at most once a
 * second. Blocking: it runs on the handler's worker thread.
 */
class PanoBackupUploader(
    private val dataDir: File,
    private val config: NodeConfig,
    /** The zip of a backup (`serverUuid, backupId, scratch file`), null when it is not here: [BackupService.backupZip]. */
    private val backupZip: (String, String, File) -> File?,
    private val logger: NodeLogger
) {
    init {
        // An upload that was under way when the daemon stopped is over: Pano gave up on it with the connection.
        File(dataDir, SCRATCH_DIRECTORY).deleteRecursively()
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun upload(message: BackupUploadMessage): JsonObject {
        val uuid = message.serverUuid
        val backupId = message.backupId
        val ticket = message.ticket

        if (uuid.isNullOrBlank() || backupId.isNullOrBlank() || ticket.isNullOrBlank() || !PathSafety.isSafeSegment(ticket)) {
            return FileService.failure(FileService.ERROR_NOT_FOUND)
        }

        val scratch = File(File(dataDir, SCRATCH_DIRECTORY), ticket)
        val pano = PanoCalls(ticket)

        return try {
            scratch.mkdirs()

            pano.progress(PHASE_ARCHIVING, 0, 0)

            val zip = backupZip(uuid, backupId, File(scratch, "backup.zip"))
                ?: return FileService.failure(FileService.ERROR_NOT_FOUND)
            val meta = JsonObject(message.meta ?: "{}")

            when (message.mode) {
                MODE_ARCHIVE -> uploadArchive(message, zip, meta, scratch, pano)
                MODE_SNAPSHOT -> uploadSnapshot(message, zip, meta, scratch, pano)
                else -> FileService.failure(ERROR_UNKNOWN_MODE)
            }
        } catch (e: Exception) {
            logger.warn("Could not send backup $backupId of $uuid to Pano Backup: ${e.message}")

            FileService.failure(e.message?.takeIf { it.isNotBlank() } ?: ERROR_UPLOAD_FAILED)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun uploadArchive(message: BackupUploadMessage, zip: File, meta: JsonObject, scratch: File, pano: PanoCalls): JsonObject {
        val archive = File(scratch, "backup.panoarc")
        val encryption = message.encryptionKey?.let { key ->
            PanoArcEncryption.DerivedKey(
                Base64.getDecoder().decode(key),
                PanoArcKdf("argon2id", message.kdfT ?: 0, message.kdfMKiB ?: 0, message.kdfP ?: 0, Base64.getDecoder().decode(message.kdfSalt ?: ""))
            )
        }

        try {
            followFile(archive, zip.length()) { done, total -> pano.progress(PHASE_ARCHIVING, done, total) }.use {
                McServerArchive.wrap(zip, meta, archive, encryption, message.producer ?: "pano-node", message.instanceName)
            }
        } finally {
            encryption?.masterKey?.fill(0)
        }

        val size = archive.length()
        val sha256 = sha256(archive)
        val session = pano.call("archive", JsonObject().put("size", size).put("sha256", sha256))
        val partSize = session.getLong("partSize") ?: 0L
        val parts = (session.getJsonArray("parts") ?: JsonArray()).map { it as JsonObject }.sortedBy { it.getInteger("partNumber") }

        if (partSize <= 0 || parts.isEmpty()) {
            throw IOException("Pano Backup answered an unusable upload session.")
        }

        var offset = 0L

        parts.forEachIndexed { index, part ->
            val length = if (index == parts.size - 1) size - offset else minOf(partSize, size - offset)
            val start = offset

            if (length < 0 || (length == 0L && size > 0)) throw IOException("The upload session does not fit the archive.")

            withRetry { putSlice(part.getString("url"), archive, start, length) { sent -> pano.progress(PHASE_UPLOADING, start + sent, size) } }

            offset += length
        }

        pano.progress(PHASE_UPLOADING, size, size, force = true)

        return JsonObject().put("ok", true).put("size", size).put("sha256", sha256)
    }

    private fun uploadSnapshot(message: BackupUploadMessage, zip: File, meta: JsonObject, scratch: File, pano: PanoCalls): JsonObject {
        val session = SnapshotSession(
            id = message.snapshotId ?: throw IOException("No snapshot to upload into."),
            keyId = message.snapshotKeyId ?: throw IOException("No snapshot key id."),
            key = Base64.getDecoder().decode(message.snapshotKey ?: ""),
            repoConfig = SnapshotFormat.parseConfig((message.repoConfig ?: "").toByteArray(Charsets.UTF_8)).validate(),
            manifestUrl = message.manifestUrl ?: throw IOException("No manifest URL.")
        )
        val remote = object : SnapshotRemote {
            override fun missing(snapshotId: String, chunks: List<Pair<String, Int>>): Set<String> =
                pano.call(
                    "missing",
                    JsonObject().put("snapshotId", snapshotId).put("chunks", JsonArray(chunks.map { JsonObject().put("id", it.first).put("size", it.second) }))
                ).getJsonArray("missing", JsonArray()).map { it as String }.toSet()

            override fun packSlot(snapshotId: String, size: Long): PackSlot {
                val slot = pano.call("pack", JsonObject().put("snapshotId", snapshotId).put("size", size))

                return PackSlot(slot.getString("packId") ?: throw IOException("No pack id."), slot.getString("url") ?: throw IOException("No pack URL."))
            }

            override fun putFile(url: String, file: File) = withRetry { putSlice(url, file, 0, file.length()) {} }

            override fun putBytes(url: String, bytes: ByteArray) = withRetry { put(url, HttpRequest.BodyPublishers.ofByteArray(bytes)) }
        }

        val stats = McServerSnapshotUpload(remote, session, zip, meta, File(scratch, "snapshot"), message.snapshotName ?: "", message.createdAt ?: System.currentTimeMillis()) { done, total ->
            pano.progress(PHASE_UPLOADING, done, total)
        }.run().stats

        return JsonObject().put("ok", true).put("stats", stats.toJson())
    }

    /** PUTs `file[offset, offset + length)` to the presigned [url], telling [onSent] the bytes read so far. */
    private fun putSlice(url: String, file: File, offset: Long, length: Long, onSent: (Long) -> Unit) {
        val body = if (length == 0L) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream { slice(file, offset, length, onSent) }, length)
        }

        put(url, body)
    }

    private fun put(url: String, body: HttpRequest.BodyPublisher) {
        val request = HttpRequest.newBuilder(URI(url)).timeout(STORAGE_TIMEOUT).PUT(body).build()
        val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()

        if (status !in 200..299) {
            // 408, 429 and 5xx are worth another try; any other answer will not change.
            val text = "The storage upload failed with HTTP $status."

            throw if (status == 408 || status == 429 || status >= 500) IOException(text) else Final(text)
        }
    }

    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0

        while (true) {
            attempt++

            try {
                return block()
            } catch (e: IOException) {
                if (attempt >= RETRY_ATTEMPTS || e is Final) throw e
            }

            Thread.sleep(RETRY_BASE_DELAY_MS shl (attempt - 1))
        }
    }

    /** A storage answer another try will not change. */
    private class Final(message: String) : IOException(message)

    /** Calls Pano under one upload ticket: `POST /api/v1/node/pano-backup/<ticket>/<action>`. */
    private inner class PanoCalls(private val ticket: String) {
        private var lastProgress = 0L

        fun call(action: String, body: JsonObject): JsonObject {
            val request = HttpRequest.newBuilder(URI.create("${config.platformUrl.trimEnd('/')}${NodePaths.PANO_BACKUP}$ticket/$action"))
                .timeout(CALL_TIMEOUT)
                .header("Authorization", "Bearer ${config.token}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.encode()))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                throw IOException(NodePaths.errorCode(response.body()) ?: "Pano answered HTTP ${response.statusCode()}.")
            }

            return runCatching { JsonObject(response.body()) }.getOrElse { JsonObject() }
        }

        /** At most one a second (and always when [force]d): a lost one only leaves Pano's bar a moment behind. */
        fun progress(phase: String, done: Long, total: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()

            if (!force && now - lastProgress < PROGRESS_INTERVAL_MS) return

            lastProgress = now

            runCatching { call("progress", JsonObject().put("phase", phase).put("done", done).put("total", total)) }
        }
    }

    /** Tells [onProgress] how far [file] has grown against [total] every half second, until closed. */
    private fun followFile(file: File, total: Long, onProgress: (Long, Long) -> Unit): AutoCloseable {
        val watcher = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    onProgress(minOf(file.length(), total), total)
                    Thread.sleep(FOLLOW_MS)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; name = "pano-backup-follow"; start() }

        return AutoCloseable { watcher.interrupt() }
    }

    companion object {
        const val MODE_ARCHIVE = "ARCHIVE"
        const val MODE_SNAPSHOT = "SNAPSHOT"

        /** The job phases Pano shows, by the names its panel knows. */
        const val PHASE_ARCHIVING = "ARCHIVING"
        const val PHASE_UPLOADING = "UPLOADING"

        const val ERROR_UNKNOWN_MODE = "UNKNOWN_MODE"
        const val ERROR_UPLOAD_FAILED = "PANO_BACKUP_UPLOAD_FAILED"

        /** `<data>/pano-backup-uploads/<ticket>/`: the archive or the snapshot's zip while it goes up, removed after. */
        const val SCRATCH_DIRECTORY = "pano-backup-uploads"

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val CALL_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val STORAGE_TIMEOUT: Duration = Duration.ofMinutes(30)
        private const val RETRY_ATTEMPTS = 4
        private const val RETRY_BASE_DELAY_MS = 1000L
        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val FOLLOW_MS = 500L
        private const val SLICE_PROGRESS_STEP = 1L * 1024 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")

            file.inputStream().buffered(256 * 1024).use { input ->
                val buffer = ByteArray(256 * 1024)

                while (true) {
                    val read = input.read(buffer)

                    if (read < 0) break

                    digest.update(buffer, 0, read)
                }
            }

            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** `file[offset, offset + length)` as a stream, telling [onRead] the bytes read so far (every 1 MB and at the end). */
        fun slice(file: File, offset: Long, length: Long, onRead: (Long) -> Unit = {}): InputStream {
            val raf = RandomAccessFile(file, "r")

            raf.seek(offset)

            return object : FilterInputStream(Channels.newInputStream(raf.channel)) {
                private var remaining = length
                private var reported = 0L

                private fun report() {
                    val done = length - remaining

                    if (done - reported >= SLICE_PROGRESS_STEP || remaining <= 0) {
                        reported = done
                        onRead(done)
                    }
                }

                override fun read(): Int {
                    if (remaining <= 0) return -1

                    return super.read().also { if (it >= 0) { remaining--; report() } }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1

                    val read = super.read(b, off, minOf(len.toLong(), remaining).toInt())

                    if (read > 0) {
                        remaining -= read
                        report()
                    }

                    return read
                }

                override fun close() {
                    super.close()
                    raf.close()
                }
            }
        }
    }
}
