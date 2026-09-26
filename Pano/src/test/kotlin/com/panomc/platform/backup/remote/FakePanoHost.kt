package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.backup.PanoBackupException
import com.panomc.platform.backup.PanoBackupInfo
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupRunner
import com.panomc.platform.backup.PanoBackupService
import com.panomc.platform.archive.PanoArcKeys
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Where the fake control plane keeps archives: in memory (served by the fake itself) or a real bucket. */
interface ArchiveStore {
    /** Opens an upload of [size] bytes; returns (partSize, part URLs). */
    fun open(key: String, size: Long): Pair<Long, List<String>>

    /** Completes the upload and returns the stored size (null = not every part arrived). */
    fun complete(key: String): Long?

    fun downloadUrl(key: String): String

    fun read(key: String): ByteArray?

    fun delete(key: String)
}

/**
 * A stand-in for the pano-host control plane's link routes (shapes of host-api.md §Link as W6/T6
 * implemented them) plus an in-memory S3 for presigned part PUTs / GETs on the same port.
 */
class FakePanoHost(private val vertx: Vertx, val port: Int, storeFactory: ((FakePanoHost) -> ArchiveStore)? = null) {
    val baseUrl = "http://127.0.0.1:$port"
    val store: ArchiveStore = storeFactory?.invoke(this) ?: MemoryStore(this)

    // Control-plane state
    val approved = ConcurrentHashMap<LinkPurpose, Boolean>()
    private val pendingPolls = ConcurrentHashMap<String, LinkPurpose>()
    val tokens = ConcurrentHashMap<String, LinkPurpose>()
    val backups = ConcurrentHashMap<String, JsonObject>()
    val transfers = ConcurrentHashMap<String, JsonObject>()
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var subscription = true

    @Volatile
    var minIntervalMinutes = 60L

    // In-memory S3 state
    val parts = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    val objects = ConcurrentHashMap<String, ByteArray>()

    /** `key#partNumber` → how many more PUTs of it answer 500. */
    val failPuts = ConcurrentHashMap<String, Int>()

    @Volatile
    var partSize = 4096L

    @Volatile
    var failAllPuts = false

    private lateinit var server: HttpServer

    fun start(): FakePanoHost {
        val router = Router.router(vertx)

        router.route().handler(BodyHandler.create().setBodyLimit(64L * 1024 * 1024))
        router.route("/host/*").handler { context -> answer(context) }
        router.put("/s3/*").handler { context -> s3Put(context) }
        router.get("/s3/*").handler { context -> s3Get(context) }

        server = runBlocking {
            vertx.createHttpServer().requestHandler(router).listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().get()
        }

        return this
    }

    fun stop() {
        server.close().toCompletionStage().toCompletableFuture().get()
    }

    private class Failure(val status: Int, val code: String, val extras: JsonObject = JsonObject()) : Exception(code)

    private fun answer(context: RoutingContext) {
        val method = context.request().method().name()
        val path = context.request().path()

        calls.add("$method $path")

        vertx.executeBlocking(Callable { route(method, path, context.request().getHeader("Authorization"), context.body()?.asJsonObject() ?: JsonObject()) })
            .onComplete { result ->
                val response = context.response().putHeader("Content-Type", "application/json")

                if (result.succeeded()) {
                    response.end(JsonObject().put("result", "ok").put("data", result.result()).encode())
                } else {
                    val failure = result.cause() as? Failure ?: Failure(500, "INTERNAL", JsonObject().put("message", result.cause().message))

                    response.setStatusCode(failure.status)
                        .end(failure.extras.copy().put("result", "error").put("error", failure.code).encode())
                }
            }
    }

    private fun auth(header: String?, purpose: LinkPurpose): String {
        val token = header?.removePrefix("Bearer ")?.takeIf { header.startsWith("Bearer ") } ?: throw Failure(401, "INVALID_TOKEN")
        val bound = tokens[token] ?: throw Failure(401, "INVALID_TOKEN")

        if (bound != purpose) throw Failure(403, "NO_PERMISSION", JsonObject().put("reason", "LINK_SCOPE"))

        return token
    }

    private fun route(method: String, path: String, authorization: String?, body: JsonObject): JsonObject {
        val segments = path.removePrefix("/host/").split("/")

        return when {
            method == "POST" && path == "/host/link/start" -> {
                val purpose = LinkPurpose.valueOf(body.getString("purpose"))
                val pollToken = UUID.randomUUID().toString()

                pendingPolls[pollToken] = purpose

                JsonObject().put("code", "ABCD-1234").put("verifyUrl", "https://panomc.test/host/link?code=ABCD-1234")
                    .put("pollToken", pollToken).put("expiresIn", 600).put("interval", 1)
            }

            method == "POST" && path == "/host/link/poll" -> {
                val purpose = pendingPolls[body.getString("pollToken")] ?: throw Failure(404, "LINK_NOT_FOUND")

                if (approved[purpose] != true) return JsonObject().put("status", "PENDING").put("interval", 1)

                pendingPolls.remove(body.getString("pollToken"))

                val token = "hlt_" + UUID.randomUUID()

                tokens[token] = purpose

                JsonObject().put("status", "ACTIVE").put("token", token).put("linkId", UUID.randomUUID().toString())
                    .put("purpose", purpose.name).put("workloadId", if (purpose == LinkPurpose.TRANSFER) "p-test00001" else null)
            }

            segments.getOrNull(1) == "backups" -> backupsRoute(method, segments, auth(authorization, LinkPurpose.BACKUP), body)
            segments.getOrNull(1) == "transfers" -> transfersRoute(method, segments, auth(authorization, LinkPurpose.TRANSFER), body)
            else -> throw Failure(404, "NOT_FOUND")
        }
    }

    private fun tier() = JsonObject().put("id", "backup-10").put("storageGb", 10).put("retentionDays", 30).put("minIntervalMinutes", minIntervalMinutes)

    private fun backupsRoute(method: String, segments: List<String>, token: String, body: JsonObject): JsonObject {
        val id = segments.getOrNull(2)
        val action = segments.getOrNull(3)

        return when {
            id == null && method == "GET" -> JsonObject()
                .put("backups", JsonArray(backups.values.filter { it.getString("status") == "DONE" }.sortedByDescending { it.getLong("createdAt") }))
                .put("tier", if (subscription) tier() else null)
                .put("usage", JsonObject().put("usedBytes", backups.values.sumOf { it.getLong("sizeBytes") }).put("reservedBytes", 0).put("quotaBytes", 10L shl 30))

            id == null && method == "POST" -> {
                if (!subscription) throw Failure(402, "PAYMENT_REQUIRED", JsonObject().put("reason", "NO_SUBSCRIPTION"))

                val kind = body.getString("kind", "pano-instance")
                val subject = body.getString("subject")
                val last = backups.values.filter { it.getString("kind") == kind && it.getString("subject") == subject }.maxOfOrNull { it.getLong("createdAt") }

                if (last != null && System.currentTimeMillis() - last < minIntervalMinutes * 60_000) {
                    throw Failure(400, "QUOTA_EXCEEDED", JsonObject().put("reason", "FREQUENCY").put("nextAllowedAt", last + minIntervalMinutes * 60_000))
                }

                val backupId = UUID.randomUUID().toString()
                val size = body.getLong("size")
                val (partSize, urls) = store.open("backups/$backupId", size)

                backups[backupId] = JsonObject().put("id", backupId).put("kind", kind).put("subject", subject).put("status", "UPLOADING")
                    .put("sizeBytes", size).put("createdAt", System.currentTimeMillis()).put("own", true)

                session("backupId", backupId, partSize, urls)
            }

            action == "complete" -> {
                val backup = backups[id] ?: throw Failure(404, "BACKUP_NOT_FOUND")
                val stored = store.complete("backups/$id") ?: throw Failure(400, "INVALID_INPUT", JsonObject().put("reason", "not_uploaded"))

                if (stored != backup.getLong("sizeBytes")) throw Failure(400, "INVALID_INPUT", JsonObject().put("reason", "size_mismatch"))

                backup.put("status", "DONE").put("sha256", body.getString("sha256")).put("manifest", body.getJsonObject("manifest"))

                JsonObject().put("backup", backup)
            }

            action == "download" -> {
                val backup = backups[id]?.takeIf { it.getString("status") == "DONE" } ?: throw Failure(404, "BACKUP_NOT_FOUND")

                JsonObject().put("url", store.downloadUrl("backups/$id")).put("expiresAt", System.currentTimeMillis() + 3600_000)
                    .put("sizeBytes", backup.getLong("sizeBytes")).put("sha256", backup.getString("sha256"))
            }

            method == "DELETE" -> {
                backups.remove(id) ?: throw Failure(404, "BACKUP_NOT_FOUND")
                store.delete("backups/$id")

                JsonObject()
            }

            else -> throw Failure(404, "NOT_FOUND")
        }
    }

    private fun transfersRoute(method: String, segments: List<String>, token: String, body: JsonObject): JsonObject {
        val id = segments.getOrNull(2)
        val action = segments.getOrNull(3)

        return when {
            id == null && method == "GET" -> JsonObject()
                .put("workload", JsonObject().put("id", "p-test00001").put("name", "Test").put("maxBytes", 10L shl 30))
                .put("transfers", JsonArray(transfers.values.toList()))

            id == null && method == "POST" -> {
                val transferId = UUID.randomUUID().toString()
                val size = body.getLong("size")
                val (partSize, urls) = store.open("imports/$transferId", size)

                transfers[transferId] = JsonObject().put("id", transferId).put("status", "UPLOADING").put("sizeBytes", size)

                session("transferId", transferId, partSize, urls)
            }

            action == "complete" -> {
                val transfer = transfers[id] ?: throw Failure(404, "TRANSFER_NOT_FOUND")

                store.complete("imports/$id") ?: throw Failure(400, "INVALID_INPUT", JsonObject().put("reason", "not_uploaded"))
                transfer.put("status", "AWAITING_CONFIRMATION")

                JsonObject().put("transfer", transfer)
            }

            method == "GET" -> JsonObject().put("transfer", transfers[id] ?: throw Failure(404, "TRANSFER_NOT_FOUND"))

            method == "DELETE" -> {
                val transfer = transfers[id] ?: throw Failure(404, "TRANSFER_NOT_FOUND")

                transfer.put("status", "CANCELED")

                JsonObject().put("canceled", true)
            }

            else -> throw Failure(404, "NOT_FOUND")
        }
    }

    private fun session(idKey: String, id: String, partSize: Long, urls: List<String>) = JsonObject()
        .put(idKey, id)
        .put("partSize", partSize)
        .put("parts", JsonArray(urls.mapIndexed { index, url -> JsonObject().put("partNumber", index + 1).put("url", url) }))
        .put("expiresAt", System.currentTimeMillis() + 86_400_000)

    // In-memory S3: PUT /s3/<key>?partNumber=n, GET /s3/<key>

    private fun s3Put(context: RoutingContext) {
        val key = context.request().path().removePrefix("/s3/")
        val partNumber = context.request().getParam("partNumber")?.toInt() ?: 1
        val failures = if (failAllPuts) 1 else failPuts["$key#$partNumber"] ?: 0

        if (failures != 0) {
            if (failures > 0 && !failAllPuts) failPuts["$key#$partNumber"] = failures - 1

            context.response().setStatusCode(if (failures < 0) 403 else 500).end()

            return
        }

        parts.computeIfAbsent(key) { ConcurrentHashMap() }[partNumber] = context.body().buffer()?.bytes ?: ByteArray(0)
        context.response().putHeader("ETag", "\"$partNumber\"").end()
    }

    private fun s3Get(context: RoutingContext) {
        val bytes = objects[context.request().path().removePrefix("/s3/")]

        if (bytes == null) context.response().setStatusCode(404).end() else context.response().end(Buffer.buffer(bytes))
    }

    class MemoryStore(private val host: FakePanoHost) : ArchiveStore {
        private val sizes = ConcurrentHashMap<String, Long>()

        override fun open(key: String, size: Long): Pair<Long, List<String>> {
            val count = maxOf(1L, (size + host.partSize - 1) / host.partSize).toInt()

            sizes[key] = size
            host.parts.remove(key)

            return host.partSize to (1..count).map { "${host.baseUrl}/s3/$key?partNumber=$it&uploadId=u1" }
        }

        override fun complete(key: String): Long? {
            val received = host.parts[key] ?: return null
            val count = maxOf(1L, ((sizes[key] ?: 0L) + host.partSize - 1) / host.partSize).toInt()

            if ((1..count).any { !received.containsKey(it) }) return null

            val bytes = (1..count).fold(ByteArray(0)) { all, n -> all + received.getValue(n) }

            host.objects[key] = bytes

            return bytes.size.toLong()
        }

        override fun downloadUrl(key: String) = "${host.baseUrl}/s3/$key"

        override fun read(key: String): ByteArray? = host.objects[key]

        override fun delete(key: String) {
            host.objects.remove(key)
        }
    }
}

/**
 * A [PanoBackupRunner] without a database: [archiveTo] writes a real (small) pano-instance archive,
 * [restoreFrom] verifies the downloaded archive with the passphrase and records its manifest.
 */
class FakeRunner(private val scope: CoroutineScope, private val payloadBytes: Int = 20_000) : PanoBackupRunner {
    private val mutex = Mutex()
    val restored = CopyOnWriteArrayList<ArchiveManifest>()
    val archived = CopyOnWriteArrayList<Boolean>()

    @Volatile
    override var job: PanoBackupJob? = null

    override fun isBusy() = mutex.isLocked

    override fun startTask(type: PanoBackupJob.Type, cleanup: suspend () -> Unit, block: suspend (PanoBackupJob) -> Unit): PanoBackupJob {
        if (!mutex.tryLock()) throw PanoBackupException(PanoBackupService.BUSY)

        val job = PanoBackupJob(UUID.randomUUID().toString(), type).also { this.job = it }

        scope.launch {
            var error: Throwable? = null

            try {
                block(job)
            } catch (e: Throwable) {
                error = e
            } finally {
                cleanup()
                mutex.unlock()
            }

            job.finishedAt = System.currentTimeMillis()

            if (error == null) {
                job.status = PanoBackupJob.Status.DONE
            } else {
                job.error = PanoBackupService.describe(error).first
                job.message = error.message
                job.details = (error as? PanoHostException)?.extras
                job.status = PanoBackupJob.Status.FAILED
            }
        }

        return job
    }

    override suspend fun archiveTo(file: File, passphrase: CharArray?): ArchiveManifest = withContext(Dispatchers.IO) {
        val encryption = passphrase?.takeIf { it.isNotEmpty() }?.let { PanoArcEncryption.Passphrase(it.copyOf()) }

        archived.add(encryption != null)

        PanoArchive.writer(file.outputStream().buffered(), encryption).use { writer ->
            writer.addBytes("app/config.conf", "website-name = \"Test\"\n".toByteArray())
            writer.addBytes("app/file-uploads/blob.bin", Random(7).nextBytes(payloadBytes))
            writer.finish(ArchiveManifest.KIND_PANO_INSTANCE, "pano/test")
        }
    }

    override suspend fun restoreFrom(source: File, passphrase: CharArray?, safetyArchive: Boolean, maintenance: Boolean): PanoBackupInfo? =
        withContext(Dispatchers.IO) {
            restored.add(source.inputStream().buffered().use { PanoArchive.verify(it, PanoArcKeys(passphrase = passphrase)) })
            null
        }
}

suspend fun awaitJob(job: PanoBackupJob, timeoutMs: Long = 60_000): PanoBackupJob = withTimeout(timeoutMs) {
    while (job.status == PanoBackupJob.Status.RUNNING) delay(20)
    job
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
