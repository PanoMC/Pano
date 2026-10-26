package com.panomc.platform.backup.remote

import com.panomc.snapshot.ChunkCodec
import com.panomc.snapshot.FastCdc
import com.panomc.snapshot.PackWriter
import com.panomc.snapshot.SnapshotPack
import com.panomc.snapshot.SnapshotRepoConfig
import com.panomc.snapshot.SnapshotSeal
import io.vertx.core.json.JsonObject
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/*
 * The upload side of a connected Pano's snapshot repository, shared by Pano (its own snapshots) and pano-node (a managed
 * server's snapshot sent straight from the node): chunking, sealing and packing run here; what needs the control plane
 * goes through a [SnapshotRemote].
 */

/**
 * What a snapshot upload asks of the control plane and the storage, blocking. Pano answers it with its own connection;
 * pano-node through Pano (the two control-plane questions) and straight to the storage (the bytes).
 */
interface SnapshotRemote {
    /** The ids of [chunks] (id, raw size; at most [MAX_BATCH]) the repository does not hold yet. */
    fun missing(snapshotId: String, chunks: List<Pair<String, Int>>): Set<String>

    /** Reserves a pack of exactly [size] bytes (checked against the quota) and returns its id and PUT URL. */
    fun packSlot(snapshotId: String, size: Long): PackSlot

    /** PUTs all of [file] to the presigned [url]. */
    fun putFile(url: String, file: File)

    /** PUTs [bytes] to the presigned [url]. */
    fun putBytes(url: String, bytes: ByteArray)

    companion object {
        /** Chunk ids one `missing` (or chunk lookup) asks about at most. */
        const val MAX_BATCH = 1000
    }
}

/** `POST /host/connected/snapshots` / `…/:id/open`: the sealing key of the Pano's repository and the manifest URL (PUT when starting, GET when opening). */
class SnapshotSession(val id: String, val keyId: String, val key: ByteArray, val repoConfig: SnapshotRepoConfig, val manifestUrl: String)

/** A pack upload slot: the pack's id (the sealed index is bound to it) and its presigned PUT. */
data class PackSlot(val id: String, val url: String)

/** The numbers `…/:id/complete` takes (`downloadBytes` = the size of the plain `.panoarc` of this snapshot). */
data class SnapshotStats(val fileCount: Long, val logicalBytes: Long, val downloadBytes: Long, val chunkCount: Long, val newChunks: Long, val newBytes: Long) {
    fun toJson(): JsonObject = JsonObject().put("fileCount", fileCount).put("logicalBytes", logicalBytes).put("downloadBytes", downloadBytes)
        .put("chunkCount", chunkCount).put("newChunks", newChunks).put("newBytes", newBytes)
}

/** What a finished snapshot upload came to. */
class SnapshotUploadResult(val stats: SnapshotStats)

/**
 * Push-side FastCDC: cuts exactly where [FastCdc.split] would for the same bytes (a chunk is cut whenever a full `max`
 * window is buffered, the rest on [finish]). One window of `max` bytes is all it holds.
 */
class ChunkingOutputStream(private val cdc: FastCdc, private val onChunk: (ByteArray, Int, Int) -> Unit) : OutputStream() {
    private val buffer = ByteArray(cdc.maxSize)
    private var filled = 0

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var left = len

        while (left > 0) {
            val n = minOf(left, buffer.size - filled)

            System.arraycopy(b, offset, buffer, filled, n)
            filled += n
            offset += n
            left -= n

            if (filled == buffer.size) cutOne()
        }
    }

    private fun cutOne() {
        val size = cdc.cut(buffer, 0, filled)

        onChunk(buffer, 0, size)
        System.arraycopy(buffer, size, buffer, 0, filled - size)
        filled -= size
    }

    /** Cuts what is left (the end of the file). */
    fun finish() {
        while (filled > 0) cutOne()
    }
}

/** Copies everything read from [input] into [copy] (not closed). */

/**
 * Packs the chunks the repository lacks: sealed chunks go into a [PackWriter] spooled in [file]; once its chunk bytes
 * reach [targetBytes] (and on [finish]) the pack's exact size is worked out, a slot of that size is reserved at Pano
 * Host (quota checked there), the index is sealed for the id it hands out and the file is PUT. Blocking.
 */
class SnapshotPackUploader(
    private val remote: SnapshotRemote,
    private val snapshotId: String,
    private val aesKey: ByteArray,
    private val keyId: String,
    private val file: File,
    private val targetBytes: Long
) : AutoCloseable {
    private var writer: PackWriter? = null

    /** Packs uploaded, the chunks in them and their sealed bytes. */
    var packs = 0
        private set
    var chunks = 0L
        private set
    var bytes = 0L
        private set

    /** Appends the sealed chunk [id]; a chunk the open pack already holds is left out. */
    fun add(id: String, sealed: ByteArray) {
        if (writer?.fits(sealed.size) == false) upload()

        val open = writer ?: PackWriter(file).also { writer = it }

        if (!open.add(id, sealed)) return

        chunks++
        bytes += sealed.size

        if (open.chunkBytes >= targetBytes) upload()
    }

    fun finish() = upload()

    private fun upload() {
        val open = writer ?: return

        // header + blobs + sealed index (independent of the pack id) + its u32 length
        val size = SnapshotPack.HEADER_BYTES + open.chunkBytes + SnapshotSeal.overhead(keyId) + 4L + open.count.toLong() * SnapshotPack.ENTRY_BYTES + 4L
        val slot = remote.packSlot(snapshotId, size)

        open.finish(aesKey, keyId, slot.id)
        writer = null

        check(file.length() == size) { "The pack file is ${file.length()} bytes, expected $size." }

        remote.putFile(slot.url, file)
        packs++
        file.delete()
    }

    override fun close() {
        writer?.close()
        file.delete()
    }
}

/**
 * Registers the chunks of one snapshot. A chunk seen for the first time is spooled raw to [spoolFile]; every
 * [SnapshotRemote.MAX_BATCH] ids (or [maxSpoolBytes]) Pano Host is asked which ones the repository lacks and
 * only those are encoded ([ChunkCodec]), sealed (bound to the chunk name) and handed to the packs. A chunk repeated
 * inside the snapshot is spooled and packed once. Disk use: the spool (at most [maxSpoolBytes] + one chunk) + one open pack.
 */
class SnapshotChunker(
    private val remote: SnapshotRemote,
    private val snapshotId: String,
    private val aesKey: ByteArray,
    private val keyId: String,
    spoolFile: File,
    packBytes: Long,
    private val maxSpoolBytes: Long = 64L * 1024 * 1024
) : AutoCloseable {
    private class Pending(val id: String, val offset: Long, val length: Int)

    private val seen = HashSet<String>()
    private val pending = ArrayList<Pending>()
    private val spool = RandomAccessFile(spoolFile, "rw")
    private val spoolPath = spoolFile
    private var spoolSize = 0L
    private val packs = SnapshotPackUploader(remote, snapshotId, aesKey, keyId, File(spoolFile.parentFile, spoolFile.name + ".pack"), packBytes)
    private val codec = ChunkCodec()

    /** Distinct chunks this snapshot references. */
    val chunkCount: Int get() = seen.size
    val newChunks: Long get() = packs.chunks
    val newBytes: Long get() = packs.bytes

    /** Registers `buffer[offset, offset + length)` and returns its id. */
    fun add(buffer: ByteArray, offset: Int, length: Int): String {
        val id = ChunkCodec.sha256Hex(buffer, offset, length)

        if (seen.add(id)) {
            spool.seek(spoolSize)
            spool.write(buffer, offset, length)
            pending += Pending(id, spoolSize, length)
            spoolSize += length

            if (pending.size >= SnapshotRemote.MAX_BATCH || spoolSize >= maxSpoolBytes) flush()
        }

        return id
    }

    /** Asks for everything pending and packs what is missing. */
    fun flush() {
        if (pending.isEmpty()) return

        val wanted = remote.missing(snapshotId, pending.map { it.id to it.length })

        pending.filter { it.id in wanted }.forEach { chunk ->
            val raw = ByteArray(chunk.length)
            val buffer = ByteBuffer.wrap(raw)

            while (buffer.hasRemaining()) {
                if (spool.channel.read(buffer, chunk.offset + buffer.position()) < 0) throw java.io.IOException("The chunk spool ended early.")
            }

            packs.add(chunk.id, SnapshotSeal.sealChunk(aesKey, keyId, chunk.id, codec.encode(raw)))
        }

        pending.clear()
        spoolSize = 0
        spool.setLength(0)
    }

    /** [flush], then uploads the last pack: afterwards every chunk of the snapshot is stored. */
    fun finish() {
        flush()
        packs.finish()
    }

    override fun close() {
        runCatching { codec.close() }
        runCatching { spool.close() }
        spoolPath.delete()
        packs.close()
    }
}
