package com.panomc.platform.archive

import com.panomc.platform.archive.PanoArcException.Code
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams an archive zip into [output] (a file, an [PanoArcEnvelope.encrypt] stream, an upload
 * pipe): nothing is buffered beyond one copy buffer, every entry is sha256-hashed while it is
 * written, and `manifest.json` goes in last by [finish], when all hashes are known.
 *
 * Collection follows the node ZipStream rules: symbolic links are never followed, they are skipped
 * and listed in the manifest's `skipped[]`, as are sockets, devices and files that vanish mid-walk.
 * Entry and byte limits stop the writer the way ZipTool's do; the zip is then left unfinished so a
 * reader sees a broken archive, never a smaller one that looks complete.
 */
class ArchiveWriter(
    output: OutputStream,
    private val limits: ArchiveLimits = ArchiveLimits()
) : AutoCloseable {
    private val zip = ZipOutputStream(output)
    private val entries = mutableListOf<ArchiveEntry>()
    private val names = HashSet<String>()
    private val skipped = mutableListOf<String>()
    private val buffer = ByteArray(BUFFER_SIZE)
    private var totalBytes = 0L
    private var finished = false

    val entryList: List<ArchiveEntry> get() = entries.toList()
    val skippedList: List<String> get() = skipped.toList()

    /** Adds [input] (read to EOF, not closed) as [name]. */
    fun addStream(name: String, input: InputStream): ArchiveEntry {
        check(!finished) { "The archive is already finished." }

        val path = ArchivePaths.validate(name)

        if (path == ArchiveManifest.MANIFEST_ENTRY) {
            throw PanoArcException(Code.UNSAFE_ENTRY, "$name is reserved.")
        }

        if (!names.add(path)) {
            throw PanoArcException(Code.INVALID_ARCHIVE, "Duplicate entry $path.")
        }

        if (entries.size + 1 > limits.maxEntries) {
            throw PanoArcException(Code.TOO_MANY_ENTRIES)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L

        zip.putNextEntry(ZipEntry(path))

        while (true) {
            val read = input.read(buffer)

            if (read < 0) {
                break
            }

            if (read == 0) {
                continue
            }

            size += read
            totalBytes += read

            if (totalBytes > limits.maxTotalBytes || size > limits.maxEntryBytes) {
                throw PanoArcException(Code.TOO_LARGE)
            }

            digest.update(buffer, 0, read)
            zip.write(buffer, 0, read)
        }

        zip.closeEntry()

        return ArchiveEntry(path, size, ArchiveManifest.hex(digest.digest())).also { entries.add(it) }
    }

    fun addBytes(name: String, bytes: ByteArray) = addStream(name, bytes.inputStream())

    /** Adds one file as [name]; a symlink or non-regular file is skipped (listed under [name]). */
    fun addFile(name: String, file: File): ArchiveEntry? {
        val path = file.toPath()

        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            skipped.add(ArchivePaths.validate(name))

            return null
        }

        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { addStream(name, it) }
    }

    /**
     * Adds everything under [root] as `<prefix>/<relative path>`, in sorted order, without following
     * symlinks. [exclude] gets the path relative to [root] (forward slashes) and prunes whole
     * directories when it matches one. A missing [root] adds nothing.
     */
    fun addTree(root: File, prefix: String, exclude: (String) -> Boolean = { false }) {
        val rootPath = root.toPath()

        if (Files.isSymbolicLink(rootPath)) {
            skipped.add(ArchivePaths.validate(prefix))

            return
        }

        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return
        }

        walk(rootPath, "", ArchivePaths.validate(prefix), exclude)
    }

    private fun walk(directory: Path, relative: String, prefix: String, exclude: (String) -> Boolean) {
        val children = Files.list(directory).use { stream -> stream.toArray().map { it as Path } }
            .sortedBy { it.fileName.toString() }

        children.forEach { child ->
            val childRelative = if (relative.isEmpty()) child.fileName.toString() else "$relative/${child.fileName}"

            if (exclude(childRelative)) {
                return@forEach
            }

            val name = "$prefix/$childRelative"

            when {
                Files.isSymbolicLink(child) -> skipped.add(name)
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> walk(child, childRelative, prefix, exclude)
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> try {
                    Files.newInputStream(child, LinkOption.NOFOLLOW_LINKS).use { addStream(name, it) }
                } catch (e: java.nio.file.NoSuchFileException) {
                    skipped.add(name)
                }

                else -> skipped.add(name)
            }
        }
    }

    /**
     * Writes `manifest.json` as the last entry and finishes the zip (the underlying stream is left
     * open; [close] closes it). [dbDumpTool] fills `db` when a [ArchiveManifest.DB_DUMP_ENTRY] was added.
     */
    fun finish(
        kind: String,
        producer: String,
        source: ArchiveSource = ArchiveSource(),
        pano: ArchivePanoInfo? = null,
        dbDumpTool: String = "pano-native",
        createdAt: Long = System.currentTimeMillis()
    ): ArchiveManifest {
        check(!finished) { "The archive is already finished." }

        val dump = entries.firstOrNull { it.path == ArchiveManifest.DB_DUMP_ENTRY }

        val manifest = ArchiveManifest(
            kind = kind,
            createdAt = createdAt,
            producer = producer,
            source = source,
            pano = pano,
            db = dump?.let { ArchiveDbInfo("mariadb", dbDumpTool, it.size, it.sha256) },
            files = ArchiveManifest.filesInfo(entries),
            entries = entries.toList(),
            skipped = skipped.toList()
        )

        zip.putNextEntry(ZipEntry(ArchiveManifest.MANIFEST_ENTRY))
        zip.write(manifest.encode())
        zip.closeEntry()
        zip.finish()
        zip.flush()

        finished = true

        return manifest
    }

    /** Closes the underlying stream (for an envelope: seals the final chunk). */
    override fun close() {
        zip.close()
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** A stream wrapper whose close() only flushes, for writing an archive into a stream the caller keeps. */
        fun nonClosing(output: OutputStream): OutputStream = object : FilterOutputStream(output) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = out.flush()
        }
    }
}

/** Limits for writing and reading archives; the defaults are generous, callers pass the real quota. */
data class ArchiveLimits(
    val maxEntries: Int = 200_000,
    val maxTotalBytes: Long = 64L * 1024 * 1024 * 1024,
    val maxEntryBytes: Long = 64L * 1024 * 1024 * 1024,
    val maxManifestBytes: Int = 64 * 1024 * 1024
)

/** Entry-name rules shared by writer and reader. */
object ArchivePaths {
    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * Returns [name] if it is a safe relative archive path: forward slashes, no empty, `.` or `..`
     * segment, no leading slash, backslash, drive letter or control character.
     */
    fun validate(name: String): String {
        val bad = name.isEmpty() || name.length > 4096 || name.startsWith("/") || name.endsWith("/") ||
                name.contains('\\') || DRIVE.containsMatchIn(name) || name.any { it < ' ' } ||
                name.split('/').any { it.isEmpty() || it == "." || it == ".." }

        if (bad) {
            throw PanoArcException(Code.UNSAFE_ENTRY, "Unsafe entry name: $name")
        }

        return name
    }
}
