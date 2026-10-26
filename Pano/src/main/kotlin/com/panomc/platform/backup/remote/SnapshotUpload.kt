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

        SnapshotChunker(client.snapshotRemote, session.id, aesKey, session.keyId, File(dir, "spool"), session.repoConfig.pack ?: error("No pack size.")).use { chunker ->
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
