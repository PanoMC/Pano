package com.panomc.platform.backup.remote

import com.panomc.snapshot.PackReader
import com.panomc.snapshot.SnapshotFormat
import com.panomc.snapshot.SnapshotSeal
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The snapshot routes of the control plane (`/host/connected/snapshots…`, host-api.md "Snapshots of connected Panos")
 * over an in-memory repository per Pano: presigned PUT / GET keys under `snap/`, the pack size check of a signed
 * `content-length`, the quota at start and per pack, and a `complete` that reads the sealed manifest and the pack
 * indexes the way the real one does (so a wrong pack size or a missing chunk fails the snapshot).
 */
class FakeSnapshotRepo(private val host: FakePanoHost) {
    val key = ByteArray(32) { (it * 7 + 1).toByte() }
    val keyId = "k1"
    private val aesKey = SnapshotSeal.deriveKey(key)

    /** `repoConfig.pack` handed out (the real one is 32 MiB; the minimum a repository may have is 1 MiB). */
    @Volatile
    var packBytes = 1L shl 20

    /** `complete` answers FAILED with this error when set. */
    @Volatile
    var failComplete: String? = null

    private class Snapshot(val view: JsonObject, val instanceId: String) {
        val packs = ArrayList<String>()
        var manifestKey: String? = null

        /** The chunks its manifest references (set when it is settled). */
        var chunkIds: Set<String> = emptySet()
    }

    private class Chunk(val pack: String, val offset: Long, val length: Int)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val reserved = ConcurrentHashMap<String, Long>()

    /** Chunks the repository holds as confirmed (instance id → chunk id). */
    private val chunks = ConcurrentHashMap<String, Chunk>()

    /** Presigned PUTs that arrived by kind, and the `complete` bodies. */
    val packPuts = CopyOnWriteArrayList<String>()
    val manifestPuts = CopyOnWriteArrayList<String>()
    val completes = CopyOnWriteArrayList<JsonObject>()

    /** Chunk ids asked about in `chunks` calls (how often a run negotiated). */
    val chunkQueries = CopyOnWriteArrayList<Int>()

    fun reset() {
        snapshots.clear()
        objects.clear()
        reserved.clear()
        chunks.clear()
        packPuts.clear()
        manifestPuts.clear()
        completes.clear()
        chunkQueries.clear()
        failComplete = null
        packBytes = 1L shl 20
    }

    fun storedBytes(): Long = snapshots.values.filter { it.view.getString("status") == "DONE" }
        .sumOf { snapshot -> snapshot.packs.sumOf { reserved[it] ?: 0L } + (snapshot.manifestKey?.let { objects[it]?.size?.toLong() } ?: 0L) }

    fun reservedBytes(): Long = snapshots.values.filter { it.view.getString("status") == "RUNNING" }.sumOf { snapshot -> snapshot.packs.sumOf { reserved[it] ?: 0L } }

    fun views(instanceId: String): List<JsonObject> = snapshots.values.filter { it.instanceId == instanceId }.map { it.view.copy() }.sortedByDescending { it.getLong("createdAt") }

    fun instancesWithoutBackups(withBackups: Set<String?>): List<String> = snapshots.values.map { it.instanceId }.distinct().filter { it !in withBackups }

    /** Plants a finished snapshot of another Pano (so a list shows it with `own: false`). */
    fun plantOther(instanceId: String, name: String): String {
        val id = UUID.randomUUID().toString()

        snapshots[id] = Snapshot(
            JsonObject().put("id", id).put("instanceId", instanceId).put("instanceName", "Other Pano").put("name", name).put("status", "DONE")
                .put("createdAt", System.currentTimeMillis()).put("fileCount", 1).put("logicalBytes", 1).put("downloadBytes", 1).put("uniqueBytes", 1),
            instanceId
        )

        return id
    }

    fun openSnapshots(instanceId: String) = snapshots.values.count { it.instanceId == instanceId && it.view.getString("status") == "RUNNING" }

    fun snapshotIds(): Set<String> = snapshots.keys.toSet()

    /** Damages one stored pack (flips a byte in its first chunk). */
    fun corruptAPack() {
        val key = objects.keys.first { it.startsWith("snap/packs/") }
        val bytes = objects.getValue(key).copyOf()

        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        objects[key] = bytes
    }

    // The presigned PUT: the status the storage answers.
    fun put(key: String, body: ByteArray): Int {
        if (key.startsWith("snap/packs/")) {
            val packId = key.removePrefix("snap/packs/")
            val size = reserved[packId] ?: return 403

            if (body.size.toLong() != size) return 403

            packPuts += packId
        } else if (key.startsWith("snap/manifests/")) {
            manifestPuts += key
        } else {
            return 403
        }

        objects[key] = body

        return 200
    }

    fun get(key: String): ByteArray? = objects[key]

    fun route(method: String, segments: List<String>, instanceId: String, body: JsonObject): JsonObject {
        val id = segments.getOrNull(1)
        val action = segments.getOrNull(2)

        if (id == null) {
            return if (method == "GET") {
                JsonObject().put("snapshots", JsonArray(views(instanceId).map { it.put("own", true) }))
                    .put("repository", JsonObject().put("storedBytes", storedBytes()))
            } else {
                start(instanceId, body)
            }
        }

        val snapshot = snapshots[id] ?: throw FakePanoHost.Failure(404, "SNAPSHOT_NOT_FOUND")

        return when {
            method == "DELETE" -> {
                if (snapshot.instanceId != instanceId) throw FakePanoHost.Failure(403, "NO_PERMISSION", JsonObject().put("reason", "OTHER_PANO"))

                snapshots.remove(id)

                // Like the control plane's collection: a pack another snapshot of this Pano still reads from stays.
                val needed = snapshots.values.filter { it.instanceId == instanceId }
                    .flatMap { other -> other.chunkIds.mapNotNull { chunks["$instanceId/$it"]?.pack } }.toSet()

                snapshot.packs.filter { it !in needed }.forEach { objects.remove("snap/packs/$it"); reserved.remove(it) }
                snapshot.manifestKey?.let { objects.remove(it) }

                JsonObject().put("deleted", true)
            }

            action == "chunks" -> {
                open(snapshot, instanceId)

                val asked = body.getJsonArray("chunks").map { (it as JsonObject).getString("id") }

                chunkQueries += asked.size

                JsonObject().put("missing", JsonArray(asked.filter { !chunks.containsKey("$instanceId/$it") }))
            }

            action == "packs" -> {
                open(snapshot, instanceId)

                val size = body.getLong("size")
                val quota = quota()

                if (host.snapshotUsed() + size > quota) {
                    throw FakePanoHost.Failure(409, "QUOTA_EXCEEDED", JsonObject().put("reason", "QUOTA").put("quotaBytes", quota).put("usedBytes", host.snapshotUsed()))
                }

                val packId = UUID.randomUUID().toString().replace("-", "")

                reserved[packId] = size
                snapshot.packs += packId

                JsonObject().put("packId", packId).put("url", "${host.baseUrl}/s3/snap/packs/$packId").put("expiresAt", System.currentTimeMillis() + 43_200_000)
            }

            action == "complete" -> {
                open(snapshot, instanceId)
                completes += body

                JsonObject().put("snapshot", settle(snapshot, instanceId, body))
            }

            action == "open" -> {
                if (snapshot.view.getString("status") != "DONE") throw FakePanoHost.Failure(404, "SNAPSHOT_NOT_FOUND", JsonObject().put("reason", snapshot.view.getString("status")))

                JsonObject().put("keyId", keyId).put("key", Base64.getEncoder().encodeToString(key)).put("repoConfig", repoConfig())
                    .put("manifestGet", JsonObject().put("url", "${host.baseUrl}/s3/snap/manifests/$id").put("expiresAt", System.currentTimeMillis() + 43_200_000))
            }

            action == "chunk-urls" -> {
                if (snapshot.view.getString("status") != "DONE") throw FakePanoHost.Failure(404, "SNAPSHOT_NOT_FOUND")

                val ids = body.getJsonArray("ids").map { it as String }
                val found = ids.mapNotNull { chunkId -> chunks.entries.firstOrNull { it.key.endsWith("/$chunkId") }?.let { chunkId to it.value } }
                val known = found.map { it.first }.toSet()

                JsonObject()
                    .put("chunks", JsonArray(found.map { (chunkId, c) -> JsonObject().put("id", chunkId).put("pack", c.pack).put("offset", c.offset).put("length", c.length) }))
                    .put("packs", JsonArray(found.map { it.second.pack }.distinct().map { JsonObject().put("id", it).put("url", "${host.baseUrl}/s3/snap/packs/$it") }))
                    .put("missing", JsonArray(ids.filter { it !in known }))
            }

            else -> throw FakePanoHost.Failure(404, "NOT_FOUND")
        }
    }

    private fun quota(): Long = if (host.subscription) host.quotaBytes else host.freeQuotaBytes ?: 0L

    private fun repoConfig() = JsonObject().put("version", 2).put("chunker", "fastcdc").put("min", 262144).put("avg", 1048576).put("max", 4194304).put("pack", packBytes)

    private fun open(snapshot: Snapshot, instanceId: String) {
        if (snapshot.instanceId != instanceId || snapshot.view.getString("status") != "RUNNING") {
            throw FakePanoHost.Failure(404, "SNAPSHOT_NOT_FOUND", JsonObject().put("reason", snapshot.view.getString("status")))
        }
    }

    private fun start(instanceId: String, body: JsonObject): JsonObject {
        if (host.lapsed) throw FakePanoHost.Failure(402, "PAYMENT_REQUIRED", JsonObject().put("reason", "LAPSED"))
        if (!host.subscription && host.freeQuotaBytes == null) throw FakePanoHost.Failure(402, "PAYMENT_REQUIRED", JsonObject().put("reason", "NO_SUBSCRIPTION"))

        val quota = quota()

        if (host.snapshotUsed() >= quota) {
            throw FakePanoHost.Failure(409, "QUOTA_EXCEEDED", JsonObject().put("reason", "QUOTA").put("quotaBytes", quota).put("usedBytes", host.snapshotUsed()))
        }

        snapshots.entries.firstOrNull { it.value.instanceId == instanceId && it.value.view.getString("status") == "RUNNING" }?.let {
            throw FakePanoHost.Failure(409, "SNAPSHOT_BUSY", JsonObject().put("snapshotId", it.key))
        }

        val id = UUID.randomUUID().toString()
        val view = JsonObject().put("id", id).put("instanceId", instanceId).put("instanceName", "My Server").put("name", body.getString("name"))
            .put("kind", if (body.getString("subject") == null) "pano-instance" else "mc-server").put("subject", body.getString("subject"))
            .put("status", "RUNNING").put("createdAt", System.currentTimeMillis())

        snapshots[id] = Snapshot(view, instanceId)

        return JsonObject().put("snapshotId", id).put("keyId", keyId).put("key", Base64.getEncoder().encodeToString(key)).put("repoConfig", repoConfig())
            .put("manifestPut", JsonObject().put("url", "${host.baseUrl}/s3/snap/manifests/$id").put("expiresAt", System.currentTimeMillis() + 86_400_000))
    }

    private fun settle(snapshot: Snapshot, instanceId: String, stats: JsonObject): JsonObject {
        fun fail(error: String): JsonObject {
            snapshot.view.put("status", "FAILED").put("error", error).put("finishedAt", System.currentTimeMillis())
            snapshot.packs.forEach { reserved.remove(it) }

            return snapshot.view
        }

        failComplete?.let { return fail(it) }

        val id = snapshot.view.getString("id")
        val manifestKey = "snap/manifests/$id"
        val sealed = objects[manifestKey] ?: return fail("MANIFEST_MISSING")
        val manifest = runCatching { SnapshotFormat.decodeManifest(SnapshotSeal.open({ aesKey }, SnapshotFormat.manifestPath(id), sealed)) }.getOrNull() ?: return fail("INTEGRITY_FAILED")

        val scope = if (snapshot.view.getString("subject") == null) SnapshotFormat.SCOPE_PANO_INSTANCE else McServerSnapshot.SCOPE

        if (manifest.scope != scope || manifest.id != id) return fail("INTEGRITY_FAILED")

        val found = HashMap<String, Chunk>()

        for (packId in snapshot.packs) {
            val bytes = objects["snap/packs/$packId"] ?: return fail("PACK_SIZE_MISMATCH")

            if (bytes.size.toLong() != reserved[packId]) return fail("PACK_SIZE_MISMATCH")

            val file = Files.createTempFile("fake-pack", ".bin").toFile()

            try {
                file.writeBytes(bytes)

                PackReader(file, packId) { aesKey }.use { reader ->
                    reader.entries.forEach { found["$instanceId/${it.id}"] = Chunk(packId, it.offset, it.length) }
                }
            } catch (e: Exception) {
                return fail("INTEGRITY_FAILED")
            } finally {
                file.delete()
            }
        }

        if (manifest.chunkIds().any { !chunks.containsKey("$instanceId/$it") && !found.containsKey("$instanceId/$it") }) return fail("MISSING_CHUNKS")

        chunks.putAll(found)
        snapshot.chunkIds = manifest.chunkIds().toSet()
        snapshot.manifestKey = manifestKey
        objects[manifestKey] = sealed
        snapshot.view.put("status", "DONE").put("finishedAt", System.currentTimeMillis()).put("fileCount", manifest.fileCount).put("logicalBytes", manifest.logicalBytes)
            .put("downloadBytes", stats.getLong("downloadBytes")).put("uniqueBytes", 0L).put("error", null)

        return snapshot.view
    }
}
