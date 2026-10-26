package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchivePaths
import com.panomc.snapshot.FastCdc
import com.panomc.snapshot.SnapshotEntry
import com.panomc.snapshot.SnapshotFormat
import com.panomc.snapshot.SnapshotManifest
import com.panomc.snapshot.SnapshotSeal
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * A managed server's snapshot in the connected Pano's repository (scope [SCOPE]): `server/…` = the files of the node's
 * backup as they are inside its zip, `meta/backup.json` = its `BackupMeta`. It shares the repository (and so the
 * deduplication) with the Pano's own snapshots; the control plane tells them apart by the snapshot's subject.
 */
object McServerSnapshot {
    const val SCOPE = "MC_SERVER"
    const val SERVER_ROOT = "server"
    const val META_PATH = "meta/backup.json"
    val ROOTS = listOf(SERVER_ROOT, "meta")
}

/**
 * Takes one snapshot of a server backup [zip] (the node's full zip, or its snapshot streamed as a zip) into [session]'s
 * repository (blocking, run it on an IO thread): every file of the zip is cut with FastCDC under `server/`, the chunks
 * the repository lacks are packed and uploaded, `meta/backup.json` and the sealed manifest go last. An entry whose name
 * is not a safe relative path is left out (never followed). `downloadBytes` is the size of the zip it came from.
 */
class McServerSnapshotUpload(
    private val remote: SnapshotRemote,
    private val session: SnapshotSession,
    private val zip: File,
    private val meta: JsonObject,
    private val dir: File,
    private val name: String,
    private val createdAt: Long,
    private val onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
) {
    fun run(): SnapshotUploadResult {
        val aesKey = SnapshotSeal.deriveKey(session.key)
        val cdc = FastCdc.of(session.repoConfig)
        val entries = ArrayList<SnapshotEntry>()
        val paths = HashSet<String>()
        var chunked = 0L

        McServerSnapshot.ROOTS.forEach { entries += SnapshotEntry(it, SnapshotEntry.TYPE_DIRECTORY, m = createdAt); paths += it }

        dir.mkdirs()

        ZipFile(zip).use { archive ->
            val items = archive.entries().toList()
            val total = maxOf(items.filter { !it.isDirectory }.sumOf { maxOf(it.size, 0L) }, 1L)

            SnapshotChunker(remote, session.id, aesKey, session.keyId, File(dir, "spool"), session.repoConfig.pack ?: error("No pack size.")).use { chunker ->
                fun cut(bytes: ByteArray): List<String> {
                    val ids = ArrayList<String>()

                    ChunkingOutputStream(cdc) { buffer, offset, length -> ids += chunker.add(buffer, offset, length) }.also {
                        it.write(bytes, 0, bytes.size)
                        it.finish()
                    }

                    return ids
                }

                for (item in items) {
                    val path = safePath(item.name) ?: continue

                    if (!paths.add(path)) continue

                    // The parents a zip may leave implicit, so the tree reads the same either way.
                    parentsOf(path).forEach { parent -> if (paths.add(parent)) entries += SnapshotEntry(parent, SnapshotEntry.TYPE_DIRECTORY, m = createdAt) }

                    val modified = item.lastModifiedTime?.toMillis() ?: createdAt

                    if (item.isDirectory) {
                        entries += SnapshotEntry(path, SnapshotEntry.TYPE_DIRECTORY, m = modified)

                        continue
                    }

                    val ids = ArrayList<String>()
                    var size = 0L
                    val out = ChunkingOutputStream(cdc) { buffer, offset, length ->
                        ids += chunker.add(buffer, offset, length)
                        chunked += length
                        onProgress(minOf(chunked, total), total)
                    }

                    archive.getInputStream(item).use { input ->
                        val buffer = ByteArray(64 * 1024)

                        while (true) {
                            val read = input.read(buffer)

                            if (read < 0) break

                            out.write(buffer, 0, read)
                            size += read
                        }
                    }

                    out.finish()
                    entries += SnapshotEntry(path, SnapshotEntry.TYPE_FILE, size, modified, ids)
                }

                val metaBytes = meta.encode().toByteArray(Charsets.UTF_8)

                entries += SnapshotEntry(McServerSnapshot.META_PATH, SnapshotEntry.TYPE_FILE, metaBytes.size.toLong(), createdAt, cut(metaBytes))

                chunker.finish()

                val manifest = SnapshotManifest(
                    version = SnapshotFormat.FORMAT_VERSION, id = session.id, name = name, createdAt = createdAt, scope = McServerSnapshot.SCOPE,
                    include = emptyList(), exclude = emptyList(), roots = McServerSnapshot.ROOTS, entries = entries
                ).sorted()
                val sealed = SnapshotSeal.seal(aesKey, session.keyId, SnapshotFormat.manifestPath(session.id), SnapshotFormat.encodeManifest(manifest))

                remote.putBytes(session.manifestUrl, sealed)
                onProgress(total, total)

                return SnapshotUploadResult(
                    SnapshotStats(manifest.fileCount, manifest.logicalBytes, zip.length(), chunker.chunkCount.toLong(), chunker.newChunks, chunker.newBytes)
                )
            }
        }
    }

    /** `server/<name>` for a zip entry name that is a safe relative path, null otherwise. */
    private fun safePath(name: String): String? {
        val trimmed = name.trimEnd('/')

        if (trimmed.isEmpty()) return null

        val path = "${McServerSnapshot.SERVER_ROOT}/$trimmed"

        return runCatching { ArchivePaths.validate(path) }.getOrNull()?.let { path }
    }

    private fun parentsOf(path: String): List<String> {
        val parts = path.split('/')

        return (2 until parts.size).map { parts.subList(0, it).joinToString("/") }
    }
}
