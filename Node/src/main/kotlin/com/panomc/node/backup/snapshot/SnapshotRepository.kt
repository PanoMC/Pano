package com.panomc.node.backup.snapshot

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.node.agent.AgentFiles
import com.panomc.node.backup.BackupScope
import com.panomc.node.backup.ResolvedScope
import com.panomc.node.backup.WorldReplacement
import com.panomc.node.files.BackupExcludeMatcher
import com.panomc.node.files.FileService
import com.panomc.node.files.ServerFileDenylist
import com.panomc.node.util.PathSafety
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An incremental, deduplicated backup repository for one server — restic's idea, in plain Kotlin.
 *
 * Every snapshot is a complete, independently restorable view of its scope, yet costs only the
 * bytes that changed since the ones before it. Files are cut into content-defined chunks by
 * [FastCdc], every chunk is stored once under its own SHA-256, and a snapshot is nothing but a
 * manifest listing which chunks rebuild which file. A second snapshot of a 10 GB network where one
 * world moved a little writes a few megabytes of chunks and a manifest; deleting the first one
 * later frees exactly the chunks nothing else still points at.
 *
 * The layout, byte-identical between pano-node and pano-mc-plugin:
 * ```
 * repo/config.json                  chunker parameters, see SnapshotRepoConfig
 * repo/data/<hh>/<sha256>           one chunk per file, <hh> = the first two hex characters
 * repo/snapshots/<id>.json.gz       the manifest, gzip-compressed JSON
 * repo/lock                         held while anything writes or deletes
 * ```
 * A chunk file is one header byte and a payload: `0x00` for the raw bytes, `0x01` for a
 * `java.util.zip.Deflater` stream at level 6 (zlib-wrapped, not nowrap). Region files are already
 * compressed and stay raw — deflate is only kept when it saves at least 5 % — while configs,
 * player data and logs shrink a lot.
 *
 * Crash safety comes from ordering alone, with no journal: chunks are written first, each to a
 * `.tmp` and moved into place, and the manifest is moved into place last. A node killed halfway
 * through leaves chunks no manifest mentions and no half-snapshot anybody could try to restore;
 * the next garbage collection sweeps the orphans. A chunk file is never rewritten once it exists,
 * because its name is its content.
 *
 * One operation at a time: create, delete-with-GC and restore take [LOCK_FILE] and a second one
 * fails with [ERROR_REPO_BUSY] instead of waiting, because the caller is a task Pano is watching
 * and a busy answer is something a person can act on while a silent queue is not. A GC running
 * under a create would delete the chunks it had just written but not yet named in a manifest.
 */
class SnapshotRepository(val root: File) {
    private val dataDir = File(root, DATA_DIRECTORY)
    private val snapshotsDir = File(root, SNAPSHOTS_DIRECTORY)
    private val configFile = File(root, CONFIG_FILE)
    private val lockFile = File(root, LOCK_FILE)

    /** The config this repository was created with, written with the defaults on first use. */
    fun config(): SnapshotRepoConfig {
        if (configFile.isFile) {
            val config = gson.fromJson(configFile.readText(), SnapshotRepoConfig::class.java)

            check(config.version == FORMAT_VERSION && config.chunker == CHUNKER_NAME) {
                "This snapshot repository was written by a newer or different Pano (${config.chunker} v${config.version})."
            }

            return config
        }

        val config = SnapshotRepoConfig()

        root.mkdirs()

        writeAtomically(configFile) { output -> output.write(compactGson.toJson(config).toByteArray()) }

        return config
    }

    /** Whether a manifest named [id] is in this repository. */
    fun has(id: String): Boolean = PathSafety.isSafeSegment(id) && manifestFile(id).isFile

    /** The ids of every snapshot in the repository, in no particular order. */
    fun ids(): List<String> = snapshotsDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(MANIFEST_SUFFIX) }
        ?.map { it.name.removeSuffix(MANIFEST_SUFFIX) }
        .orEmpty()

    /** The manifest of [id], or null when there is none. Throws when it exists but cannot be read. */
    fun manifest(id: String): SnapshotManifest? {
        if (!has(id)) {
            return null
        }

        return readManifest(manifestFile(id))
    }

    /**
     * Takes a snapshot of [scope] inside [serverDirectory].
     *
     * [onProgress] gets the task's percentages as the contract lays them out: 10 while scanning,
     * 10 to 85 by bytes processed, 90 while the manifest is written. It is called often; the
     * [com.panomc.node.task.TaskReporter] throttle is what turns that into a sane number of frames.
     */
    fun create(
        serverDirectory: File,
        id: String,
        name: String,
        createdAt: Long,
        scope: ResolvedScope,
        include: List<String>,
        exclude: BackupExcludeMatcher,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): SnapshotResult {
        require(PathSafety.isSafeSegment(id)) { "Rejected an unusable snapshot id." }

        return locked {
            val config = config()
            val chunker = FastCdc(config.min, config.avg, config.max)

            onProgress(SCAN_PERCENT, "Scanning files")

            val scanned = scan(serverDirectory, scope.roots, exclude)
            val parent = newestManifest()
                ?.entries
                ?.filter { it.isFile && it.c != null }
                ?.associateBy { it.p }
                .orEmpty()

            val files = scanned.filter { it.isFile }
            val totalBytes = files.sumOf { it.size }.coerceAtLeast(1)

            val writer = ChunkWriter()
            val referenced = HashSet<String>()
            val entries = ArrayList<SnapshotEntry>(scanned.size)

            var processed = 0L
            var fileIndex = 0

            fun report() {
                val percent = SCAN_PERCENT + ((BYTES_PERCENT_SPAN * processed) / totalBytes).toInt()

                onProgress(percent.coerceAtMost(SCAN_PERCENT + BYTES_PERCENT_SPAN), "Backing up $fileIndex/${files.size} files")
            }

            scanned.forEach { item ->
                if (!item.isFile) {
                    entries.add(SnapshotEntry(p = item.path, t = SnapshotEntry.TYPE_DIRECTORY, m = item.modified))

                    return@forEach
                }

                fileIndex++

                val previous = parent[item.path]

                // Unchanged by size and time: the parent's chunk list is this file's chunk list,
                // and the file is never opened. A missing chunk (a repository somebody pruned by
                // hand) makes it a changed file instead of a snapshot that cannot be restored.
                if (previous != null && previous.s == item.size && previous.m == item.modified &&
                    previous.c.orEmpty().all { writer.exists(it) }
                ) {
                    val chunks = previous.c.orEmpty()

                    referenced.addAll(chunks)
                    entries.add(SnapshotEntry(p = item.path, t = SnapshotEntry.TYPE_FILE, s = item.size, m = item.modified, c = chunks))

                    processed += item.size

                    report()

                    return@forEach
                }

                val file = PathSafety.resolveRelative(serverDirectory, item.path)
                val chunks = ArrayList<String>()

                val size = try {
                    file.inputStream().use { input ->
                        chunker.split(input) { buffer, offset, length ->
                            val chunkId = writer.store(buffer, offset, length)

                            chunks.add(chunkId)

                            processed += length

                            report()
                        }
                    }
                } catch (exception: java.io.FileNotFoundException) {
                    // Deleted between the scan and the read — a log rotating, a plugin cleaning
                    // up. Not being in the snapshot is exactly what happened to it.
                    return@forEach
                }

                referenced.addAll(chunks)
                entries.add(SnapshotEntry(p = item.path, t = SnapshotEntry.TYPE_FILE, s = size, m = item.modified, c = chunks))
            }

            onProgress(MANIFEST_PERCENT, "Writing snapshot")

            val manifest = SnapshotManifest(
                id = id,
                name = name,
                createdAt = createdAt,
                scope = scope.kind.name,
                include = include,
                exclude = exclude.patterns,
                roots = scope.roots,
                entries = entries.sortedBy { it.p }
            )

            snapshotsDir.mkdirs()

            writeAtomically(manifestFile(id)) { output ->
                val json = GZIPOutputStream(output, IO_BUFFER).bufferedWriter()

                compactGson.toJson(manifest, json)

                // Closing the writer finishes the gzip trailer; the file stream under it is closed
                // again by writeAtomically, which is harmless.
                json.close()
            }

            SnapshotResult(manifest, writer.newChunks, referenced.size, writer.storedBytes)
        }
    }

    /**
     * Removes snapshot [id] and then every chunk no remaining snapshot needs.
     *
     * [onRemoved] runs between the two, under the same lock, for whatever else describes the
     * snapshot outside the repository (the node's `<id>.json` meta). Returns what the GC freed.
     */
    fun delete(id: String, onRemoved: () -> Unit = {}): SnapshotGcResult = locked {
        if (PathSafety.isSafeSegment(id)) {
            Files.deleteIfExists(manifestFile(id).toPath())
        }

        onRemoved()

        collectGarbage()
    }

    /** A garbage collection on its own, under the lock. */
    fun gc(): SnapshotGcResult = locked { collectGarbage() }

    /**
     * Reads every chunk snapshot [id] needs and checks it against its name.
     *
     * Throws [CorruptChunkException] for the first one that is missing or wrong. Reads the whole
     * snapshot, so it costs as much as a restore minus the writes; it is what a restore does
     * before touching anything.
     */
    fun verify(id: String) {
        val manifest = manifest(id) ?: throw NoSuchElementException("There is no snapshot $id.")

        verifyChunks(manifest)
    }

    /**
     * Puts snapshot [id] back into [target], a stopped server's directory.
     *
     * Every chunk is verified before a single byte of [target] changes, so a repository with a
     * bad disk block fails the restore with `CORRUPT_CHUNK <sha>` and leaves the server exactly as
     * it was, rather than half-restored. [beforeWrite] runs after that check and before the
     * writes — the pre-restore safety copy goes there, where it is only taken for a restore that
     * is actually going to happen.
     *
     * Every world directory in the snapshot is emptied first and rewritten whole (see
     * [WorldReplacement]); everything else is laid over what is there. Denylisted paths are never
     * written. Files and directories get their recorded modification times back.
     */
    fun restore(
        id: String,
        target: File,
        beforeWrite: () -> Unit = {},
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): SnapshotManifest = locked {
        val manifest = manifest(id) ?: throw NoSuchElementException("There is no snapshot $id.")

        onProgress(VERIFY_PERCENT, "Verifying the snapshot")

        verifyChunks(manifest)

        beforeWrite()

        val worlds = BackupScope.worldsIn(manifest.entries.asSequence().filter { it.isFile }.map { it.p })

        worlds.forEach { world -> WorldReplacement.clear(target, world) }

        val files = manifest.entries.filter { it.isFile }
        val totalBytes = manifest.logicalBytes.coerceAtLeast(1)
        var written = 0L
        var index = 0

        manifest.entries.forEach { entry ->
            val relative = ServerFileDenylist.normalise(entry.p)

            // A Pano Agent's own jar is never written over, whatever the snapshot holds (SM-74).
            if (relative.isEmpty() || ServerFileDenylist.isDenied(relative) || AgentFiles.isOwnJar(target, relative)) {
                return@forEach
            }

            val destination = PathSafety.resolveRelative(target, relative)
            val path = destination.toPath()

            if (entry.isDirectory) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && ServerFileDenylist.isMutable(relative)) {
                    Files.delete(path)
                }

                destination.mkdirs()

                return@forEach
            }

            index++

            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!ServerFileDenylist.isMutable(relative)) {
                    return@forEach
                }

                destination.deleteRecursively()
            } else if (Files.isSymbolicLink(path)) {
                Files.delete(path)
            }

            destination.parentFile?.mkdirs()

            destination.outputStream().buffered(IO_BUFFER).use { output ->
                entry.c.orEmpty().forEach { chunkId ->
                    val bytes = readChunk(chunkId)

                    output.write(bytes)

                    written += bytes.size
                }
            }

            entry.m?.let { runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(it)) } }

            val percent = RESTORE_PERCENT + ((RESTORE_PERCENT_SPAN * written) / totalBytes).toInt()

            onProgress(percent.coerceAtMost(RESTORE_PERCENT + RESTORE_PERCENT_SPAN), "Restoring $index/${files.size} files")
        }

        // Directory times last and deepest first: writing a file into a directory is itself a
        // change of that directory's time.
        manifest.entries.filter { it.isDirectory && it.m != null }.sortedByDescending { it.p.length }.forEach { entry ->
            val relative = ServerFileDenylist.normalise(entry.p)

            if (relative.isEmpty() || ServerFileDenylist.isDenied(relative)) {
                return@forEach
            }

            runCatching {
                Files.setLastModifiedTime(PathSafety.resolveRelative(target, relative).toPath(), FileTime.fromMillis(entry.m!!))
            }
        }

        manifest
    }

    /**
     * Writes snapshot [id] into [output] as an ordinary zip, built as it goes.
     *
     * What the `@backup/<id>` download of a snapshot is: there is no archive on disk to send, and
     * making one first would double the disk a big world needs just to download it. Takes no lock —
     * reading never conflicts with a create, whose chunks only ever appear whole — so a download
     * never blocks a scheduled snapshot; only deleting this very snapshot mid-download can break
     * the stream, and a broken stream is what the reader then sees.
     *
     * Throws [FileService.ERROR_TOO_LARGE] past [maxBytes] without finishing the zip. [output] is
     * finished but not closed.
     */
    fun writeZip(id: String, output: OutputStream, maxBytes: Long = Long.MAX_VALUE): Long {
        val manifest = manifest(id) ?: throw NoSuchElementException("There is no snapshot $id.")

        val zip = ZipOutputStream(output)
        var bytes = 0L

        manifest.entries.forEach { entry ->
            val name = ServerFileDenylist.normalise(entry.p)

            if (name.isEmpty() || ServerFileDenylist.isDenied(name)) {
                return@forEach
            }

            val zipEntry = ZipEntry(if (entry.isDirectory) "$name/" else name)

            entry.m?.let { runCatching { zipEntry.lastModifiedTime = FileTime.fromMillis(it) } }

            zip.putNextEntry(zipEntry)

            entry.c.orEmpty().forEach { chunkId ->
                val chunk = readChunk(chunkId)

                bytes += chunk.size

                if (bytes > maxBytes) {
                    throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                }

                zip.write(chunk)
            }

            zip.closeEntry()
        }

        zip.finish()
        zip.flush()

        return bytes
    }

    /**
     * The uncompressed bytes of chunk [id], checked against its name.
     *
     * Throws [CorruptChunkException] when the file is missing, has a header this format does not
     * know, does not inflate, or hashes to anything else.
     */
    fun readChunk(id: String): ByteArray {
        if (!CHUNK_ID.matches(id)) {
            throw CorruptChunkException(id)
        }

        val file = chunkFile(id)

        val stored = try {
            file.readBytes()
        } catch (_: Exception) {
            throw CorruptChunkException(id)
        }

        if (stored.isEmpty()) {
            throw CorruptChunkException(id)
        }

        val raw = when (stored[0]) {
            HEADER_RAW -> stored.copyOfRange(1, stored.size)
            HEADER_DEFLATE -> inflate(id, stored)
            else -> throw CorruptChunkException(id)
        }

        if (sha256Hex(raw, 0, raw.size) != id) {
            throw CorruptChunkException(id)
        }

        return raw
    }

    private fun verifyChunks(manifest: SnapshotManifest) {
        manifest.chunkIds().forEach { chunkId -> readChunk(chunkId) }
    }

    /**
     * Deletes every chunk no manifest references, and every stale `.tmp`.
     *
     * Refuses to delete anything when a manifest cannot be read: a snapshot that exists but whose
     * manifest is damaged still owns chunks nobody can list any more, and a GC that treated it as
     * absent would turn "one snapshot is unreadable" into "every chunk it shared is gone".
     */
    private fun collectGarbage(): SnapshotGcResult {
        val referenced = HashSet<String>()

        snapshotsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(TMP_SUFFIX)) {
                file.delete()

                return@forEach
            }

            if (!file.name.endsWith(MANIFEST_SUFFIX)) {
                return@forEach
            }

            val manifest = try {
                readManifest(file)
            } catch (_: Exception) {
                return SnapshotGcResult(0, 0, skipped = true)
            }

            referenced.addAll(manifest.chunkIds())
        }

        var deleted = 0
        var freed = 0L

        dataDir.listFiles()?.forEach { bucket ->
            bucket.listFiles()?.forEach { file ->
                val name = file.name

                if (name.endsWith(TMP_SUFFIX) || (CHUNK_ID.matches(name) && name !in referenced)) {
                    val length = file.length()

                    if (file.delete() && !name.endsWith(TMP_SUFFIX)) {
                        deleted++
                        freed += length
                    }
                }
            }

            if (bucket.isDirectory && bucket.list()?.isEmpty() == true) {
                bucket.delete()
            }
        }

        return SnapshotGcResult(deleted, freed)
    }

    /**
     * The newest readable manifest, which is what a new snapshot reuses unchanged files from.
     *
     * Candidates are tried by file time, newest first, and the first one that parses wins: a
     * manifest is only ever moved into place at the very end of its create, so its file time is
     * its creation time, and parsing every manifest of a long history just to compare `createdAt`
     * would cost more than the snapshot saves.
     */
    private fun newestManifest(): SnapshotManifest? = snapshotsDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(MANIFEST_SUFFIX) }
        ?.sortedByDescending { it.lastModified() }
        ?.firstNotNullOfOrNull { file -> runCatching { readManifest(file) }.getOrNull() }

    private fun readManifest(file: File): SnapshotManifest =
        GZIPInputStream(file.inputStream().buffered(IO_BUFFER)).bufferedReader().use { reader ->
            gson.fromJson(reader, SnapshotManifest::class.java)
                ?: throw IllegalStateException("The snapshot manifest ${file.name} is empty.")
        }

    /**
     * Walks [roots] under [serverDirectory], sorted, and returns what a snapshot of them holds.
     *
     * The same rules as a FULL archive: denylisted paths are skipped with everything under them,
     * symbolic links are skipped outright (following one is how a server directory ends up backing
     * up `/etc`), and excludes are asked path by path — so an excluded directory's own entry goes
     * while a child the patterns do not name stays, exactly as the zip does it.
     */
    private fun scan(serverDirectory: File, roots: List<String>, exclude: BackupExcludeMatcher): List<ScanItem> {
        val result = ArrayList<ScanItem>()

        roots.forEach { root ->
            val relative = ServerFileDenylist.normalise(root)

            if (relative.isEmpty()) {
                return@forEach
            }

            walk(PathSafety.resolveRelative(serverDirectory, relative), relative, exclude, result)
        }

        return result.distinctBy { it.path }.sortedBy { it.path }
    }

    private fun walk(file: File, relative: String, exclude: BackupExcludeMatcher, result: MutableList<ScanItem>) {
        val path = file.toPath()

        if (Files.isSymbolicLink(path) || ServerFileDenylist.isDenied(relative)) {
            return
        }

        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!exclude.matches(relative)) {
                result.add(ScanItem(relative, false, 0, modified(path)))
            }

            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                walk(child, "$relative/${child.name}", exclude, result)
            }

            return
        }

        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || exclude.matches(relative)) {
            return
        }

        result.add(ScanItem(relative, true, runCatching { Files.size(path) }.getOrDefault(0L), modified(path)))
    }

    private fun modified(path: java.nio.file.Path): Long =
        runCatching { Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L)

    private fun inflate(id: String, stored: ByteArray): ByteArray {
        val inflater = Inflater()

        try {
            inflater.setInput(stored, 1, stored.size - 1)

            val output = ByteArrayOutputStream(stored.size * 2)
            val buffer = ByteArray(IO_BUFFER)

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)

                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw CorruptChunkException(id)
                }

                output.write(buffer, 0, count)

                // No chunk is ever larger than the chunker's maximum; a payload that inflates past
                // any sane multiple of it is not one of ours.
                if (output.size() > MAX_INFLATED_BYTES) {
                    throw CorruptChunkException(id)
                }
            }

            return output.toByteArray()
        } catch (_: DataFormatException) {
            throw CorruptChunkException(id)
        } finally {
            inflater.end()
        }
    }

    /**
     * Stores chunks for one [create], remembering which ones are already known to exist.
     *
     * The deflater and its output buffer are reused across every chunk of the snapshot; the digest
     * is a fresh one per chunk, which costs nothing next to hashing a megabyte.
     */
    private inner class ChunkWriter {
        private val present = HashSet<String>()
        private val deflater = Deflater(DEFLATE_LEVEL)
        private val compressed = ByteArrayOutputStream(FastCdc.DEFAULT_MAX)
        private val deflateBuffer = ByteArray(IO_BUFFER)

        var newChunks = 0
        var storedBytes = 0L

        fun exists(id: String): Boolean {
            if (id in present) {
                return true
            }

            if (CHUNK_ID.matches(id) && chunkFile(id).isFile) {
                present.add(id)

                return true
            }

            return false
        }

        /** Stores the chunk unless it is already there, and returns its id. */
        fun store(buffer: ByteArray, offset: Int, length: Int): String {
            val id = sha256Hex(buffer, offset, length)

            if (exists(id)) {
                return id
            }

            val useDeflate = deflate(buffer, offset, length)
            val target = chunkFile(id)

            target.parentFile?.mkdirs()

            writeAtomically(target) { output ->
                if (useDeflate) {
                    output.write(HEADER_DEFLATE.toInt())
                    compressed.writeTo(output)
                } else {
                    output.write(HEADER_RAW.toInt())
                    output.write(buffer, offset, length)
                }
            }

            present.add(id)
            newChunks++
            storedBytes += target.length()

            return id
        }

        /**
         * Deflates the chunk into [compressed] and says whether that was worth it.
         *
         * Gives up as soon as the output passes 95 % of the input, which on an already-compressed
         * region file is early — most of the CPU a naive "compress, then compare" would burn on
         * incompressible bytes is never spent.
         */
        private fun deflate(buffer: ByteArray, offset: Int, length: Int): Boolean {
            val limit = length.toLong() * DEFLATE_KEEP_PERCENT / 100

            compressed.reset()
            deflater.reset()
            deflater.setInput(buffer, offset, length)
            deflater.finish()

            while (!deflater.finished()) {
                val count = deflater.deflate(deflateBuffer)

                compressed.write(deflateBuffer, 0, count)

                if (compressed.size() > limit) {
                    return false
                }
            }

            return compressed.size() <= limit
        }
    }

    private fun <T> locked(block: () -> T): T {
        root.mkdirs()

        val key = root.absoluteFile.normalize().path

        // The OS lock covers another process; this covers another thread of this one, which a
        // FileChannel lock does not reliably do across two channels on the same file.
        if (!HELD.add(key)) {
            throw RepoBusyException()
        }

        var channel: FileChannel? = null
        var lock: FileLock? = null

        try {
            channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)

            lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                throw RepoBusyException()
            }

            channel.truncate(0)
            channel.write(java.nio.ByteBuffer.wrap("${ProcessHandle.current().pid()} ${System.currentTimeMillis()}\n".toByteArray()))

            return block()
        } finally {
            if (lock != null) {
                // Removed while still held, so a lock file only outlives its operation when the
                // process died inside it — and then nothing holds its OS lock any more, which is
                // what the next operation checks, however old the file is.
                runCatching { Files.deleteIfExists(lockFile.toPath()) }
                runCatching { lock.release() }
            }

            runCatching { channel?.close() }

            HELD.remove(key)
        }
    }

    private fun manifestFile(id: String): File = File(snapshotsDir, "$id$MANIFEST_SUFFIX")

    private fun chunkFile(id: String): File = File(File(dataDir, id.substring(0, 2)), id)

    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        val temporary = File(target.parentFile, "${target.name}$TMP_SUFFIX")

        try {
            temporary.outputStream().buffered(IO_BUFFER).use { output -> write(output) }

            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: Exception) {
            temporary.delete()

            throw exception
        }
    }

    private data class ScanItem(val path: String, val isFile: Boolean, val size: Long, val modified: Long)

    companion object {
        const val FORMAT_VERSION = 1
        const val CHUNKER_NAME = "fastcdc"

        const val CONFIG_FILE = "config.json"
        const val DATA_DIRECTORY = "data"
        const val SNAPSHOTS_DIRECTORY = "snapshots"
        const val LOCK_FILE = "lock"
        const val MANIFEST_SUFFIX = ".json.gz"
        const val TMP_SUFFIX = ".tmp"

        const val ERROR_REPO_BUSY = "REPO_BUSY"
        const val ERROR_CORRUPT_CHUNK = "CORRUPT_CHUNK"

        const val HEADER_RAW: Byte = 0x00
        const val HEADER_DEFLATE: Byte = 0x01

        private const val DEFLATE_LEVEL = 6
        private const val DEFLATE_KEEP_PERCENT = 95
        private const val IO_BUFFER = 64 * 1024
        private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

        private const val SCAN_PERCENT = 10
        private const val BYTES_PERCENT_SPAN = 75
        private const val MANIFEST_PERCENT = 90

        private const val VERIFY_PERCENT = 5
        private const val RESTORE_PERCENT = 15
        private const val RESTORE_PERCENT_SPAN = 80

        private val CHUNK_ID = Regex("^[0-9a-f]{64}$")

        private val HELD: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val gson: Gson = GsonBuilder().create()
        private val compactGson: Gson = GsonBuilder().disableHtmlEscaping().create()

        /** Lowercase hex SHA-256 of a slice, with a digest of its own. */
        fun sha256Hex(buffer: ByteArray, offset: Int, length: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")

            digest.update(buffer, offset, length)

            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
