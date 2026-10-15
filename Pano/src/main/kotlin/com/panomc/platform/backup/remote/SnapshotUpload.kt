package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchivePanoInfo
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.ArchiveWriter
import com.panomc.snapshot.ChunkCodec
import com.panomc.snapshot.FastCdc
import com.panomc.snapshot.PackWriter
import com.panomc.snapshot.SnapshotEntry
import com.panomc.snapshot.SnapshotFormat
import com.panomc.snapshot.SnapshotManifest
import com.panomc.snapshot.SnapshotPack
import com.panomc.snapshot.SnapshotSeal
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

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
class TeeInputStream(private val input: InputStream, private val copy: OutputStream) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)

        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)

        if (n > 0) copy.write(b, off, n)

        return n
    }

    override fun close() = input.close()
}

/** Counts what is written and throws it away. */
class CountingNullOutputStream : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}

/**
 * Packs the chunks the repository lacks: sealed chunks go into a [PackWriter] spooled in [file]; once its chunk bytes
 * reach [targetBytes] (and on [finish]) the pack's exact size is worked out, a slot of that size is reserved at Pano
 * Host (quota checked there), the index is sealed for the id it hands out and the file is PUT. Blocking.
 */
class SnapshotPackUploader(
    private val client: PanoHostClient,
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
        val slot = runBlocking { client.startSnapshotPack(snapshotId, size) }

        open.finish(aesKey, keyId, slot.id)
        writer = null

        check(file.length() == size) { "The pack file is ${file.length()} bytes, expected $size." }

        runBlocking { client.putFile(slot.url, file) }
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
 * [PanoHostClient.MAX_SNAPSHOT_BATCH] ids (or [maxSpoolBytes]) Pano Host is asked which ones the repository lacks and
 * only those are encoded ([ChunkCodec]), sealed (bound to the chunk name) and handed to the packs. A chunk repeated
 * inside the snapshot is spooled and packed once. Disk use: the spool (at most [maxSpoolBytes] + one chunk) + one open pack.
 */
class SnapshotChunker(
    private val client: PanoHostClient,
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
    private val packs = SnapshotPackUploader(client, snapshotId, aesKey, keyId, File(spoolFile.parentFile, spoolFile.name + ".pack"), packBytes)
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

            if (pending.size >= PanoHostClient.MAX_SNAPSHOT_BATCH || spoolSize >= maxSpoolBytes) flush()
        }

        return id
    }

    /** Asks for everything pending and packs what is missing. */
    fun flush() {
        if (pending.isEmpty()) return

        val wanted = runBlocking { client.snapshotMissing(snapshotId, pending.map { it.id to it.length }) }

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

/** What a finished snapshot upload came to. */
class SnapshotUploadResult(val stats: SnapshotStats)

/**
 * Takes one snapshot of [content] into [session]'s repository (blocking, run it on an IO thread): the files of the
 * instance (`app/` tree), the uncompressed dump and `meta/pano.json` are cut with FastCDC, the chunks the repository
 * lacks are packed and uploaded, the sealed manifest goes last. The same streams are teed through an [ArchiveWriter]
 * that only counts, so `downloadBytes` is the size of the plain `.panoarc` of this content (the dump gzipped once more
 * for that, into [dir]). Memory: one 4 MiB chunk window, a 64 KiB archive buffer and the chunk ids of the snapshot.
 */
class SnapshotUpload(
    private val client: PanoHostClient,
    private val session: SnapshotSession,
    private val content: SnapshotContent,
    private val dir: File,
    private val name: String,
    private val producer: String,
    private val createdAt: Long,
    private val onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
) {
    fun run(): SnapshotUploadResult {
        val aesKey = SnapshotSeal.deriveKey(session.key)
        val cdc = FastCdc.of(session.repoConfig)
        val files = SnapshotFiles(content.layout)
        val dump = content.dump?.takeIf { content.dumpTool != null && it.isFile }
        val total = maxOf(files.totalBytes() + (dump?.length() ?: 0L), 1L)
        val entries = ArrayList<SnapshotEntry>()
        val skipped = ArrayList(files.skipped)
        val counting = CountingNullOutputStream()
        val archive = ArchiveWriter(counting)
        var chunked = 0L

        SnapshotArc.ROOTS.forEach { entries += SnapshotEntry(it, SnapshotEntry.TYPE_DIRECTORY, m = createdAt) }
        files.directories.forEach { (path, modified) -> entries += SnapshotEntry(path, SnapshotEntry.TYPE_DIRECTORY, m = modified) }

        File(dir, "spool").also { it.parentFile.mkdirs() }

        SnapshotChunker(client, session.id, aesKey, session.keyId, File(dir, "spool"), session.repoConfig.pack ?: error("No pack size.")).use { chunker ->
            try {
                for (item in files.files) {
                    val ids = ArrayList<String>()
                    val cut = ChunkingOutputStream(cdc) { buffer, offset, length ->
                        ids += chunker.add(buffer, offset, length)
                        chunked += length
                        onProgress(chunked, total)
                    }
                    val input = try {
                        FileInputStream(item.file)
                    } catch (e: java.io.FileNotFoundException) {
                        if (item.file.exists()) throw e

                        // Vanished since the walk: listed as skipped, like the archive does.
                        skipped += item.path

                        continue
                    }
                    val entry = input.use { archive.addStream(item.path, TeeInputStream(it, cut)) }

                    cut.finish()
                    entries += SnapshotEntry(item.path, SnapshotEntry.TYPE_FILE, entry.size, item.file.lastModified(), ids)
                }

                if (dump != null) {
                    val ids = ArrayList<String>()
                    val gz = File(dir, "dump.sql.gz")
                    val size = dump.inputStream().buffered(256 * 1024).use { input ->
                        GZIPOutputStream(gz.outputStream().buffered(64 * 1024), 64 * 1024).use { gzip ->
                            val cut = ChunkingOutputStream(cdc) { buffer, offset, length ->
                                ids += chunker.add(buffer, offset, length)
                                chunked += length
                                onProgress(chunked, total)
                            }
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L

                            while (true) {
                                val read = input.read(buffer)

                                if (read < 0) break

                                cut.write(buffer, 0, read)
                                gzip.write(buffer, 0, read)
                                done += read
                            }

                            cut.finish()
                            done
                        }
                    }

                    entries += SnapshotEntry(SnapshotArc.DUMP_PATH, SnapshotEntry.TYPE_FILE, size, createdAt, ids)
                    archive.addFile(ArchiveManifest.DB_DUMP_ENTRY, gz)
                    gz.delete()
                }

                skipped.distinct().forEach { archive.skip(it) }

                val meta = SnapshotArc.meta(content, producer, skipped.distinct())
                val metaBytes = meta.encode().toByteArray(Charsets.UTF_8)
                val metaIds = ArrayList<String>()

                ChunkingOutputStream(cdc) { buffer, offset, length -> metaIds += chunker.add(buffer, offset, length) }.also {
                    it.write(metaBytes, 0, metaBytes.size)
                    it.finish()
                }

                entries += SnapshotEntry(SnapshotArc.META_PATH, SnapshotEntry.TYPE_FILE, metaBytes.size.toLong(), createdAt, metaIds)

                archive.finish(
                    kind = ArchiveManifest.KIND_PANO_INSTANCE,
                    producer = producer,
                    source = ArchiveSource(),
                    pano = ArchivePanoInfo(content.panoVersion, content.dbPrefix, content.schemeVersions),
                    dbDumpTool = content.dumpTool ?: "pano-native",
                    createdAt = createdAt
                )
                archive.close()

                chunker.finish()
            } catch (e: Throwable) {
                runCatching { archive.close() }

                throw e
            }

            val manifest = SnapshotManifest(
                version = SnapshotFormat.FORMAT_VERSION, id = session.id, name = name, createdAt = createdAt, scope = SnapshotFormat.SCOPE_PANO_INSTANCE,
                include = emptyList(), exclude = SnapshotArc.EXCLUDE, roots = SnapshotArc.ROOTS, entries = entries
            ).sorted()
            val sealed = SnapshotSeal.seal(aesKey, session.keyId, SnapshotFormat.manifestPath(session.id), SnapshotFormat.encodeManifest(manifest))

            runBlocking { client.putBytes(session.manifestUrl, sealed) }
            onProgress(total, total)

            return SnapshotUploadResult(
                SnapshotStats(manifest.fileCount, manifest.logicalBytes, counting.count, chunker.chunkCount.toLong(), chunker.newChunks, chunker.newBytes)
            )
        }
    }
}
