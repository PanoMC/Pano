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
 * A stand-in for the pano-host control plane's connected-Pano routes (shapes of host-api.md §Connected
 * Pano + §Pano Backup as W21 implemented them: platform connection JWT + `X-Pano-Instance-*` headers)
 * plus an in-memory S3 for presigned part PUTs / GETs on the same port.
 */
class FakePanoHost(private val vertx: Vertx, val port: Int, storeFactory: ((FakePanoHost) -> ArchiveStore)? = null) {
    val baseUrl = "http://127.0.0.1:$port"
    val store: ArchiveStore = storeFactory?.invoke(this) ?: MemoryStore(this)

    // Control-plane state
    /** Valid platform connection JWTs (a disconnect on panomc.com = removed). */
    val tokens: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add(TOKEN) }
    val backups = ConcurrentHashMap<String, JsonObject>()
    val transfers = ConcurrentHashMap<String, JsonObject>()
    val calls = CopyOnWriteArrayList<String>()

    /** `X-Pano-Instance-Id` / `-Name` of every connected call. */
    val instances = CopyOnWriteArrayList<Pair<String?, String?>>()

    @Volatile
    var subscription = true

    @Volatile
    var lapsed = false

    @Volatile
    var quotaBytes = 10L shl 30

    /** Called on every part PUT (`key`, part number) before it is stored (e.g. to stop the backup there). */
    @Volatile
    var onPart: (String, Int) -> Unit = { _, _ -> }

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

    companion object {
        const val TOKEN = "platform-jwt-test"
        const val ACCOUNT_ID = "0b8e4f6e-3c5d-4a2b-9f1e-2d3c4b5a6f70"
        const val WORKLOAD_ID = "p-test00001"
    }

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

        vertx.executeBlocking(Callable { route(method, path, context, context.body()?.asJsonObject() ?: JsonObject()) })
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

    private fun auth(header: String?): String {
        val token = header?.removePrefix("Bearer ")?.takeIf { header.startsWith("Bearer ") }
            ?: throw Failure(401, "NOT_CONNECTED", JsonObject().put("reason", "MISSING"))

        if (token !in tokens) throw Failure(401, "INVALID_TOKEN")

        return token
    }

    private fun instance(context: RoutingContext, required: Boolean): String? {
        val id = context.request().getHeader("X-Pano-Instance-Id")?.lowercase()

        instances.add(id to context.request().getHeader("X-Pano-Instance-Name"))

        if (required && (id == null || !Regex("[a-z0-9-]{8,64}").matches(id))) {
            throw Failure(400, "INVALID_INPUT", JsonObject().put("field", "X-Pano-Instance-Id").put("reason", "required"))
        }

        return id
    }

    private fun route(method: String, path: String, context: RoutingContext, body: JsonObject): JsonObject {
        auth(context.request().getHeader("Authorization"))

        val segments = path.removePrefix("/host/connected").removePrefix("/").split("/").filter { it.isNotEmpty() }

        return when {
            !path.startsWith("/host/connected") -> throw Failure(404, "NOT_FOUND")
            segments.isEmpty() -> JsonObject().put("accountId", ACCOUNT_ID).put("platformId", "p1").put("username", "tester")
                .put("instanceId", instance(context, false)).put("instanceName", context.request().getHeader("X-Pano-Instance-Name"))
            segments[0] == "backups" -> backupsRoute(method, segments, instance(context, true)!!, body)
            segments[0] == "workloads" -> JsonObject().put("workloads", JsonArray().add(JsonObject().put("id", WORKLOAD_ID).put("name", "Test").put("maxBytes", 10L shl 30)))
            segments[0] == "transfers" -> transfersRoute(method, segments, instance(context, true)!!, body)
            else -> throw Failure(404, "NOT_FOUND")
        }
    }

    private fun tier() = JsonObject().put("id", "backup-10").put("name", "10 GB").put("storageGb", 10).put("priceMonthly", 100).put("graceDays", 7)

    private fun usedBytes() = backups.values.filter { it.getString("status") == "DONE" }.sumOf { it.getLong("sizeBytes") }

    private fun backupsRoute(method: String, segments: List<String>, instanceId: String, body: JsonObject): JsonObject {
        val id = segments.getOrNull(1)
        val action = segments.getOrNull(2)
        val active = subscription && !lapsed

        return when {
            id == null && method == "GET" -> {
                val visible = backups.values.map { it.copy().put("own", it.getString("instanceId") == instanceId) }
                val panos = visible.groupBy { it.getString("instanceId") }.map { (instance, list) ->
                    JsonObject().put("instanceId", instance).put("instanceName", list.first().getString("instanceName")).put("current", instance == instanceId)
                        .put("usedBytes", list.filter { it.getString("status") == "DONE" }.sumOf { it.getLong("sizeBytes") })
                        .put("backups", JsonArray(list.sortedByDescending { it.getLong("createdAt") }))
                }

                JsonObject()
                    .put("instanceId", instanceId)
                    .put("tier", if (active) tier() else null)
                    .put("subscription", if (active) JsonObject().put("tierId", "backup-10").put("status", "ACTIVE") else null)
                    .put("usage", JsonObject().put("usedBytes", usedBytes()).put("reservedBytes", 0).put("quotaBytes", if (active) quotaBytes else null)
                        .put("freeBytes", if (active) maxOf(0L, quotaBytes - usedBytes()) else null))
                    .put("panos", JsonArray(panos))
            }

            id == null && method == "POST" -> {
                if (lapsed) throw Failure(402, "PAYMENT_REQUIRED", JsonObject().put("reason", "LAPSED").put("graceUntil", 1L))
                if (!subscription) throw Failure(402, "PAYMENT_REQUIRED", JsonObject().put("reason", "NO_SUBSCRIPTION"))

                val size = body.getLong("size")

                if (usedBytes() + size > quotaBytes) {
                    throw Failure(400, "QUOTA_EXCEEDED", JsonObject().put("reason", "QUOTA").put("quotaBytes", quotaBytes).put("usedBytes", usedBytes()))
                }

                val backupId = UUID.randomUUID().toString()
                val (partSize, urls) = store.open("backups/$backupId", size)

                backups[backupId] = JsonObject().put("id", backupId).put("instanceId", instanceId).put("instanceName", "?")
                    .put("kind", body.getString("kind", "pano-instance")).put("subject", body.getString("subject")).put("status", "UPLOADING")
                    .put("sizeBytes", size).put("createdAt", System.currentTimeMillis())

                session("backupId", backupId, partSize, urls)
            }

            action == "complete" -> {
                val backup = backups[id] ?: throw Failure(404, "BACKUP_NOT_FOUND")

                if (backup.getString("instanceId") != instanceId) throw Failure(403, "NO_PERMISSION", JsonObject().put("reason", "OTHER_PANO"))
                if (backup.getString("status") == "CANCELED") throw Failure(404, "BACKUP_NOT_FOUND", JsonObject().put("reason", "CANCELED"))

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

            method == "GET" -> JsonObject().put("backup", backups[id] ?: throw Failure(404, "BACKUP_NOT_FOUND"))

            method == "DELETE" -> {
                val backup = backups[id] ?: throw Failure(404, "BACKUP_NOT_FOUND")

                if (backup.getString("instanceId") != instanceId) throw Failure(403, "NO_PERMISSION", JsonObject().put("reason", "OTHER_PANO"))

                backups.remove(id)
                store.delete("backups/$id")

                JsonObject().put("deleted", true)
            }

            else -> throw Failure(404, "NOT_FOUND")
        }
    }

    /** What the website's "stop" does: the backup is `CANCELED` and its upload aborted. */
    fun stop(backupId: String) {
        backups[backupId]?.put("status", "CANCELED")?.put("error", "STOPPED")
        store.delete("backups/$backupId")
        parts.remove("backups/$backupId")
        aborted.add("backups/$backupId")
    }

    /** Aborted multipart uploads: their part PUTs answer 404 (as S3 does). */
    val aborted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun transfersRoute(method: String, segments: List<String>, instanceId: String, body: JsonObject): JsonObject {
        val id = segments.getOrNull(1)
        val action = segments.getOrNull(2)
        val mine = { transferId: String? -> transfers[transferId]?.takeIf { it.getString("instanceId") == instanceId } ?: throw Failure(404, "TRANSFER_NOT_FOUND") }

        return when {
            id == null && method == "GET" -> JsonObject().put("transfers", JsonArray(transfers.values.filter { it.getString("instanceId") == instanceId }))

            id == null && method == "POST" -> {
                if (body.getString("workloadId") != WORKLOAD_ID) throw Failure(404, "WORKLOAD_NOT_FOUND")

                val transferId = UUID.randomUUID().toString()
                val size = body.getLong("size")
                val (partSize, urls) = store.open("imports/$transferId", size)

                transfers[transferId] = JsonObject().put("id", transferId).put("instanceId", instanceId).put("workloadId", WORKLOAD_ID)
                    .put("status", "UPLOADING").put("sizeBytes", size)

                session("transferId", transferId, partSize, urls)
            }

            action == "complete" -> {
                val transfer = mine(id)

                store.complete("imports/$id") ?: throw Failure(400, "INVALID_INPUT", JsonObject().put("reason", "not_uploaded"))
                transfer.put("status", "AWAITING_CONFIRMATION")

                JsonObject().put("transfer", transfer)
            }

            method == "GET" -> JsonObject().put("transfer", mine(id))

            method == "DELETE" -> {
                mine(id).put("status", "CANCELED")

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
        onPart(key, partNumber)

        if (key in aborted) {
            context.response().setStatusCode(404).end()

            return
        }

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
