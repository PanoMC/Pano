package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchivePanoInfo
import com.panomc.platform.archive.ArchivePaths
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.ArchiveWriter
import com.panomc.platform.archive.db.SanitisedSqlImporter
import com.panomc.snapshot.ChunkCodec
import com.panomc.snapshot.CorruptChunkException
import com.panomc.snapshot.SnapshotEntry
import com.panomc.snapshot.SnapshotFormat
import com.panomc.snapshot.SnapshotIntegrityException
import com.panomc.snapshot.SnapshotManifest
import com.panomc.snapshot.SnapshotPack
import com.panomc.snapshot.SnapshotSeal
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPOutputStream

/**
 * Turns a snapshot of the account back into a plain `pano-instance` `.panoarc` (blocking, run it on an IO thread), the
 * archive the normal restore flow takes: `open` → the sealed manifest is fetched and unsealed → `chunk-urls` in
 * batches → every chunk is read from its pack (downloaded whole into [dir]), unsealed and checked against its id
 * ([SnapshotSeal.openChunk]) before a byte of it is used → the files stream into an [ArchiveWriter], the dump is
 * re-gzipped to `db/dump.sql.gz`, the manifest's `pano` / `db` come from `meta/pano.json`.
 *
 * Disk: the archive being written + the packs that still hold chunks that are needed later. A pack is deleted when its
 * last needed chunk is read; packs are fetched one at a time, in the order they are first needed. Worst case (every
 * pack still needed) it is the size of the repository's live packs; typically a few packs. Memory: one chunk (≤ 4 MiB)
 * and the chunk locations of the snapshot.
 */
class SnapshotRestoreArchive(
    private val client: PanoHostClient,
    private val session: SnapshotSession,
    private val dir: File,
    private val onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
) {
    private class Meta(val json: JsonObject) {
        val panoVersion: String = json.getString("panoVersion") ?: ""
        val dbPrefix: String = json.getString("dbPrefix") ?: ""
        val dumpTool: String? = json.getString("dumpTool")
        val producer: String = json.getString("producer") ?: "pano"
        val skipped: List<String> = json.getJsonArray("skipped")?.mapNotNull { it as? String } ?: emptyList()
        val schemeVersions: Map<String, Int> = json.getJsonObject("schemeVersions")?.let { scheme ->
            scheme.fieldNames().associateWith { (scheme.getValue(it) as Number).toInt() }
        } ?: emptyMap()
    }

    private val opener = SnapshotSeal.opener(mapOf(session.keyId to session.key))
    private val locations = HashMap<String, ChunkLocation>()
    private val packUrls = HashMap<String, String>()
    private val packFiles = HashMap<String, Pair<File, Long>>()
    private val remaining = HashMap<String, Int>()
    private var readBytes = 0L

    /** Builds the archive into [target] and returns its manifest. */
    fun build(target: File): ArchiveManifest {
        dir.mkdirs()

        val manifest = readManifest()
        val metaEntry = manifest.entries.firstOrNull { it.p == SnapshotArc.META_PATH } ?: throw broken("the snapshot has no meta/pano.json")

        if ((metaEntry.s ?: 0) > MAX_META_BYTES) throw broken("meta/pano.json is too large")

        val appFiles = manifest.entries.filter { it.isFile && it.p.startsWith("app/") }
        val dumpEntry = manifest.entries.firstOrNull { it.p == SnapshotArc.DUMP_PATH } ?: throw broken("the snapshot has no database dump")
        val order = listOf(metaEntry) + appFiles + dumpEntry

        resolve(order.flatMap { it.c ?: emptyList() })

        // How many reads each pack still has to serve; it is deleted at zero.
        order.flatMap { it.c ?: emptyList() }.forEach { remaining.merge(locations.getValue(it).pack, 1, Int::plus) }

        val total = maxOf(manifest.logicalBytes, 1L)
        val meta = Meta(JsonObject(String(entryStream(metaEntry).readBytes(), Charsets.UTF_8)))

        if (!SanitisedSqlImporter.PREFIX.matches(meta.dbPrefix)) throw broken("the snapshot has no valid meta/pano.json")

        val gz = File(dir, "dump.sql.gz")
        val writer = ArchiveWriter(target.outputStream().buffered(256 * 1024))

        try {
            appFiles.forEach { entry ->
                writer.addStream(entry.p, entryStream(entry))
                onProgress(minOf(readBytes, total), total)
            }

            GZIPOutputStream(gz.outputStream().buffered(64 * 1024), 64 * 1024).use { gzip -> entryStream(dumpEntry).copyTo(gzip, 64 * 1024) }

            writer.addFile(ArchiveManifest.DB_DUMP_ENTRY, gz)
            gz.delete()
            meta.skipped.forEach(writer::skip)

            return writer.finish(
                kind = ArchiveManifest.KIND_PANO_INSTANCE,
                producer = meta.producer,
                source = ArchiveSource(),
                pano = ArchivePanoInfo(meta.panoVersion, meta.dbPrefix, meta.schemeVersions),
                dbDumpTool = meta.dumpTool ?: "pano-native",
                createdAt = manifest.createdAt
            ).also { writer.close() }
        } catch (e: Throwable) {
            runCatching { writer.close() }

            throw e
        } finally {
            gz.delete()
            packFiles.values.forEach { it.first.delete() }
            packFiles.clear()
        }
    }

    private fun broken(why: String) = PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "The snapshot cannot be restored: $why.")

    private fun readManifest(): SnapshotManifest {
        val file = File(dir, "manifest.sealed")

        runBlocking { client.download(session.manifestUrl, file, null, null) }

        if (file.length() > MAX_MANIFEST_BYTES) throw broken("its manifest is too large")

        val sealed = file.readBytes()

        file.delete()

        val manifest = try {
            SnapshotFormat.decodeManifest(SnapshotSeal.open(opener, SnapshotFormat.manifestPath(session.id), sealed))
        } catch (e: SnapshotIntegrityException) {
            throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = e.message, cause = e)
        } catch (e: Exception) {
            throw broken("its manifest is unreadable")
        }

        if (manifest.version != SnapshotFormat.FORMAT_VERSION || manifest.id != session.id || manifest.scope != SnapshotFormat.SCOPE_PANO_INSTANCE) {
            throw broken("the manifest is not snapshot ${session.id} of a Pano instance")
        }

        val seen = HashSet<String>()

        manifest.entries.forEach { entry ->
            val root = entry.p.substringBefore('/')

            runCatching { ArchivePaths.validate(entry.p) }.getOrElse { throw broken("an unsafe path") }

            if (root !in SnapshotArc.ROOTS || !seen.add(entry.p)) throw broken("an unsafe or duplicate path")

            if (entry.isFile) {
                if (root != "app" && entry.p != SnapshotArc.DUMP_PATH && entry.p != SnapshotArc.META_PATH) throw broken("an unexpected file ${entry.p}")

                val chunks = entry.c

                if (chunks == null || (entry.s ?: -1) < 0 || chunks.any { !SnapshotFormat.isChunkId(it) }) throw broken("an invalid entry ${entry.p}")
            } else if (!entry.isDirectory) {
                throw broken("an invalid entry type")
            }
        }

        return manifest.sorted()
    }

    private fun resolve(ids: List<String>) {
        ids.filter { it !in locations }.distinct().chunked(PanoHostClient.MAX_SNAPSHOT_BATCH).forEach { batch ->
            val answer = runBlocking { client.snapshotChunkUrls(session.id, batch) }

            answer.missing.firstOrNull()?.let { throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "${CorruptChunkException.ERROR_CORRUPT_CHUNK} ${it.take(64)}") }
            packUrls.putAll(answer.packs)
            batch.forEach { id -> locations[id] = answer.chunks[id] ?: throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "${CorruptChunkException.ERROR_CORRUPT_CHUNK} $id") }
        }
    }

    /** The verified raw bytes of chunk [id]; its pack is downloaded first when it is not on disk, deleted after its last read. */
    private fun chunk(id: String): ByteArray {
        val at = locations.getValue(id)
        val (file, blobEnd) = packFiles.getOrPut(at.pack) { download(at.pack) }

        if (at.length < 1 || at.length > MAX_SEALED_CHUNK || at.offset < SnapshotPack.HEADER_BYTES || at.offset + at.length > blobEnd) {
            throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "${CorruptChunkException.ERROR_CORRUPT_CHUNK} $id")
        }

        val sealed = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { SnapshotPack.read(it, at.offset, at.length) }
        val raw = try {
            SnapshotSeal.openChunk(opener, id, sealed)
        } catch (e: CorruptChunkException) {
            throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = e.message, cause = e)
        } catch (e: SnapshotIntegrityException) {
            throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = e.message, cause = e)
        }
        val left = remaining.getValue(at.pack) - 1

        remaining[at.pack] = left

        if (left == 0) {
            packFiles.remove(at.pack)?.first?.delete()
        }

        return raw
    }

    private fun download(packId: String): Pair<File, Long> {
        val url = packUrls[packId] ?: throw IllegalStateException("No URL for pack $packId.")
        val file = File(dir, "pack-$packId")

        runBlocking { client.download(url, file, null, null) }

        if (file.length() > SnapshotPack.MAX_PACK_BYTES) {
            file.delete()

            throw SnapshotPack.invalid(packId, "larger than ${SnapshotPack.MAX_PACK_BYTES} bytes")
        }

        val blobEnd = try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { SnapshotPack.indexRange(it, packId).first }
        } catch (e: SnapshotIntegrityException) {
            file.delete()

            throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = e.message, cause = e)
        }

        return file to blobEnd
    }

    /** The content of [entry] chunk by chunk; its size must match the manifest. */
    private fun entryStream(entry: SnapshotEntry): InputStream = object : InputStream() {
        private val ids = (entry.c ?: emptyList()).iterator()
        private var current = ByteArray(0)
        private var position = 0
        private var total = 0L
        private var done = false

        private fun fill(): Boolean {
            while (position >= current.size) {
                if (!ids.hasNext()) {
                    if (!done) {
                        done = true

                        if (total != entry.s) throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "SNAPSHOT_SIZE_MISMATCH ${entry.p.take(200)}")
                    }

                    return false
                }

                current = chunk(ids.next())
                position = 0
                total += current.size

                if (total > (entry.s ?: 0)) throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "SNAPSHOT_SIZE_MISMATCH ${entry.p.take(200)}")
            }

            return true
        }

        override fun read(): Int = if (fill()) current[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1

            val n = minOf(len, current.size - position)

            System.arraycopy(current, position, b, off, n)
            position += n
            readBytes += n

            return n
        }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
        private const val MAX_META_BYTES = 1024 * 1024
        private const val MAX_SEALED_CHUNK = ChunkCodec.MAX_INFLATED_BYTES + 1 + 128
    }
}
