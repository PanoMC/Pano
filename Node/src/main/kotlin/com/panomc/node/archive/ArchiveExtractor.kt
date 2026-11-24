package com.panomc.node.archive

import com.panomc.node.agent.AgentFiles
import com.panomc.node.files.ServerFileDenylist
import com.panomc.node.files.ZipTool
import com.panomc.node.util.PathSafety
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Unpacks every archive format the node accepts, with the same safety rules for all of them.
 *
 * The rules are the ones [ZipTool] has always applied, now format-independent:
 * - every entry name goes through [PathSafety.resolveRelative] against the target, so an entry
 *   climbing out of it (zip slip) aborts the extraction before anything is opened;
 * - symlinks, hard links, devices and FIFOs are never created: an archive can declare a link to
 *   anywhere, and a later write through it would land outside the sandbox every check approved;
 * - [defaultSkip] keeps an archive from overwriting this server's credentials or a Pano Agent's
 *   own jar;
 * - every byte goes through a [DiskGuard], which is the only size limit there is. When it trips
 *   (or anything else fails) every file this call created is deleted again before the exception
 *   leaves, so a failed extraction never leaves half an archive behind.
 *
 * Streamable formats are read straight off the stream they arrive on; 7z and RAR need random
 * access and go through a temp file (see [extractStream]).
 */
object ArchiveExtractor {
    private const val BUFFER_SIZE = 64 * 1024

    /** The skip rule [ZipTool] applies: denied paths and the agent's own jar stay as they are. */
    fun defaultSkip(root: File): (String) -> Boolean = { relative ->
        ServerFileDenylist.isDenied(relative) || AgentFiles.isOwnJar(root, relative)
    }

    /**
     * Extracts the archive [input] is positioned at into [target] (inside [root]) and returns its
     * format. [input] is read to its end (trailing bytes after the archive are discarded) but not
     * closed.
     *
     * The format is detected on the live stream without consuming it. Streamable formats are
     * extracted as the bytes arrive; 7z and RAR are first copied (through [guard]) into a temp
     * file in [tempDirectory], extracted from there, and the temp file is always deleted.
     * [onFormat] is called once the format is known, before any extraction starts.
     *
     * An IOException from [input] itself (the upload connection) is rethrown unchanged, so a
     * dropped transfer is not reported as a corrupt archive.
     */
    fun extractStream(
        input: InputStream,
        target: File,
        root: File,
        guard: DiskGuard,
        tempDirectory: File,
        fileNameHint: String? = null,
        skip: (relative: String) -> Boolean = defaultSkip(root),
        onFormat: (ArchiveFormat) -> Unit = {}
    ): ArchiveFormat {
        val source = BufferedInputStream(SourceStream(input), BUFFER_SIZE)

        val format = try {
            ArchiveFormats.detect(source, fileNameHint)
        } catch (exception: SourceFailure) {
            throw exception.cause!!
        }

        if (format == ArchiveFormat.UNKNOWN) {
            throw unsupported()
        }

        onFormat(format)

        if (format.streamable) {
            val session = ExtractionSession(target, root, guard, skip)

            session.run {
                extractStreamable(format, source, session, fileNameHint)

                // A zip's central directory or a tar's end blocks are never read by the entry
                // readers; reading them here lets the upload finish instead of stalling on a
                // body nobody consumes.
                drain(source)
            }

            return format
        }

        tempDirectory.mkdirs()

        val temp = File.createTempFile("pano-archive-", ".${format.id}", tempDirectory)

        try {
            try {
                FileOutputStream(temp).use { raw ->
                    ExtractionSession.copy(source, guard.wrap(raw), BUFFER_SIZE)
                }
            } catch (exception: Throwable) {
                throw ExtractionSession.translate(exception, guard)
            }

            extractFile(temp, target, root, guard, skip, format, fileNameHint)
        } finally {
            temp.delete()
        }

        return format
    }

    /**
     * Extracts [archive] into [target] (inside [root]), whatever supported format it is, and
     * returns that format. Throws [ArchiveException] with `UNSUPPORTED_ARCHIVE` for anything else.
     */
    fun extractFile(
        archive: File,
        target: File,
        root: File,
        guard: DiskGuard,
        skip: (relative: String) -> Boolean = defaultSkip(root)
    ): ArchiveFormat {
        require(archive.isFile) { "The archive does not exist." }

        val format = ArchiveFormats.detect(archive)

        if (format == ArchiveFormat.UNKNOWN) {
            throw unsupported()
        }

        extractFile(archive, target, root, guard, skip, format, archive.name)

        return format
    }

    /** RAR through [tools] instead of the installed ones; how tests reach the subprocess path. */
    internal fun extractRar(
        archive: File,
        target: File,
        root: File,
        guard: DiskGuard,
        tools: List<RarExtractor.Tool>,
        skip: (relative: String) -> Boolean = defaultSkip(root),
        stallTimeoutMillis: Long = RarExtractor.STALL_TIMEOUT_MILLIS
    ) {
        val session = ExtractionSession(target, root, guard, skip)

        session.run { RarExtractor.extract(archive, session, guard, tools, stallTimeoutMillis) }
    }

    private fun extractFile(
        archive: File,
        target: File,
        root: File,
        guard: DiskGuard,
        skip: (String) -> Boolean,
        format: ArchiveFormat,
        nameHint: String?
    ) {
        val session = ExtractionSession(target, root, guard, skip)

        session.run {
            when (format) {
                ArchiveFormat.ZIP -> zipFile(archive, session)
                ArchiveFormat.SEVEN_Z -> sevenZ(archive, session)
                ArchiveFormat.RAR -> RarExtractor.extract(archive, session, guard, RarExtractor.installed())
                ArchiveFormat.UNKNOWN -> throw unsupported()
                else -> BufferedInputStream(archive.inputStream(), BUFFER_SIZE).use { input ->
                    extractStreamable(format, input, session, nameHint)
                }
            }
        }
    }

    private fun extractStreamable(format: ArchiveFormat, input: InputStream, session: ExtractionSession, nameHint: String?) {
        // The wrappers are closed to free their native inflaters; [SourceStream] (and a file
        // stream the caller owns) is shielded from that close where it matters.
        val shielded = CloseShield(input)

        when (format) {
            ArchiveFormat.ZIP -> ZipArchiveInputStream(shielded, "UTF8", true, true).use { zipStream(it, session) }
            ArchiveFormat.TAR -> TarArchiveInputStream(shielded).use { tar(it, session) }
            ArchiveFormat.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(shielded, true)).use { tar(it, session) }
            ArchiveFormat.TAR_BZ2 -> TarArchiveInputStream(BZip2CompressorInputStream(shielded, true)).use { tar(it, session) }
            ArchiveFormat.TAR_XZ -> TarArchiveInputStream(XZCompressorInputStream(shielded, true)).use { tar(it, session) }
            ArchiveFormat.GZ -> GzipCompressorInputStream(shielded, true).use { session.file(gzName(nameHint), it) }
            else -> throw unsupported()
        }
    }

    private fun zipStream(input: ZipArchiveInputStream, session: ExtractionSession) {
        try {
            while (true) {
                val entry = input.nextEntry ?: break

                zipEntry(entry, session) {
                    if (!input.canReadEntryData(entry)) {
                        throw unsupportedEntry(entry.name)
                    }

                    session.file(entry.name, input)
                }
            }
        } catch (exception: UnsupportedZipFeatureException) {
            throw ArchiveException(ArchiveException.UNSUPPORTED_ARCHIVE, "This zip uses a feature the node cannot read: ${exception.message}", exception)
        }
    }

    private fun zipFile(archive: File, session: ExtractionSession) {
        try {
            ZipFile.builder().setFile(archive).get().use { zip ->
                val entries = zip.entries

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    zipEntry(entry, session) {
                        if (!zip.canReadEntryData(entry)) {
                            throw unsupportedEntry(entry.name)
                        }

                        zip.getInputStream(entry).use { session.file(entry.name, it) }
                    }
                }
            }
        } catch (exception: UnsupportedZipFeatureException) {
            throw ArchiveException(ArchiveException.UNSUPPORTED_ARCHIVE, "This zip uses a feature the node cannot read: ${exception.message}", exception)
        }
    }

    private inline fun zipEntry(entry: ZipArchiveEntry, session: ExtractionSession, file: () -> Unit) {
        when {
            entry.isDirectory -> session.directory(entry.name)
            // Never recreated; see the class comment.
            entry.isUnixSymlink -> return
            else -> file()
        }
    }

    private fun tar(input: TarArchiveInputStream, session: ExtractionSession) {
        while (true) {
            val entry = input.nextEntry ?: break

            when {
                entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> continue
                entry.isGlobalPaxHeader || entry.isPaxHeader -> continue
                entry.isDirectory -> session.directory(entry.name)
                entry.isFile -> session.file(entry.name, input)
            }
        }
    }

    private fun sevenZ(archive: File, session: ExtractionSession) {
        // A 7z can ask for a dictionary of up to 1.5 GiB; above this the node says so instead of
        // running out of heap.
        val memoryLimitKb = (Runtime.getRuntime().maxMemory() / 4 / 1024).coerceIn(64L * 1024, Int.MAX_VALUE.toLong()).toInt()

        try {
            SevenZFile.builder().setFile(archive).setMaxMemoryLimitKiB(memoryLimitKb).get().use { sevenZ ->
                val content = object : InputStream() {
                    override fun read(): Int = sevenZ.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = sevenZ.read(b, off, len)
                }

                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    val name = entry.name?.takeIf { it.isNotBlank() } ?: archive.name.removeSuffix(".7z").ifBlank { "file" }

                    when {
                        entry.isAntiItem -> continue
                        entry.isDirectory -> session.directory(name)
                        isSevenZLink(entry) -> continue
                        !entry.hasStream() -> session.file(name, InputStream.nullInputStream())
                        else -> session.file(name, content)
                    }
                }
            }
        } catch (exception: PasswordRequiredException) {
            throw ArchiveException(ArchiveException.UNSUPPORTED_ARCHIVE, "This 7z archive is password protected.", exception)
        } catch (exception: MemoryLimitException) {
            throw ArchiveException(ArchiveException.UNSUPPORTED_ARCHIVE, "This 7z archive needs more memory to unpack than this node can spare (${exception.message}).", exception)
        }
    }

    /** 7-Zip stores a Unix mode in the attributes' high half (flag 0x8000); S_IFLNK is a link. */
    private fun isSevenZLink(entry: SevenZArchiveEntry): Boolean {
        if (!entry.hasWindowsAttributes) {
            return false
        }

        val attributes = entry.windowsAttributes

        if (attributes and 0x8000 == 0) {
            return false
        }

        val type = (attributes ushr 16) and 0xF000

        return type != 0 && type != 0x8000 && type != 0x4000
    }

    /** `world.dat.gz` unpacks to `world.dat`; anything unusable to `file`. */
    internal fun gzName(hint: String?): String {
        val base = hint?.replace('\\', '/')?.substringAfterLast('/')?.trim().orEmpty()

        val name = when {
            base.endsWith(".gz", ignoreCase = true) -> base.dropLast(3)
            base.endsWith(".gzip", ignoreCase = true) -> base.dropLast(5)
            // Same name as the archive would overwrite the archive while it is being read.
            base.isNotEmpty() -> "$base.out"
            else -> ""
        }

        return name.takeIf { PathSafety.isSafeSegment(it) && it != "." } ?: "file"
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(BUFFER_SIZE)

        while (input.read(buffer) >= 0) {
            // discarded
        }
    }

    private fun unsupported() =
        ArchiveException(ArchiveException.UNSUPPORTED_ARCHIVE, "This file is not an archive the node can unpack (zip, tar, tar.gz, tar.bz2, tar.xz, gz, 7z or rar).")

    private fun unsupportedEntry(name: String) =
        ArchiveException(ArchiveException.UNSUPPORTED_ARCHIVE, "\"$name\" is encrypted or uses a compression method the node cannot read.")

    /** Marks an IOException that came from the caller's stream rather than from decoding it. */
    internal class SourceFailure(cause: IOException) : IOException(cause.message, cause)

    private class SourceStream(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = try {
            super.read()
        } catch (exception: IOException) {
            throw SourceFailure(exception)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            `in`.read(b, off, len)
        } catch (exception: IOException) {
            throw SourceFailure(exception)
        }

        override fun skip(n: Long): Long = try {
            super.skip(n)
        } catch (exception: IOException) {
            throw SourceFailure(exception)
        }

        /** The caller owns the stream. */
        override fun close() = Unit
    }

    private class CloseShield(input: InputStream) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len)

        override fun close() = Unit
    }
}

/**
 * The bookkeeping of one extraction: where entries go, what was created, and undoing it.
 *
 * Files that did not exist before are recorded and deleted on failure; a file that existed and
 * was being overwritten when the failure hit is deleted too, because it is half-written either
 * way. Files that existed and were fully replaced stay (they hold the archive's complete copy).
 * Directories this call created are removed again when they end up empty.
 */
internal class ExtractionSession(
    target: File,
    private val root: File,
    private val guard: DiskGuard,
    private val skip: (String) -> Boolean
) {
    val target: File = target.absoluteFile

    private val createdFiles = ArrayList<File>()
    private val createdDirectories = ArrayList<File>()

    /** Runs [block]; on any failure undoes this session and throws the translated exception. */
    fun <T> run(block: () -> T): T {
        try {
            ensureDirectory(target)

            return block()
        } catch (exception: Throwable) {
            cleanup()

            throw translate(exception, guard)
        }
    }

    fun directory(name: String) {
        ensureDirectory(resolve(name))
    }

    /** Writes [input] (read to its end, not closed) to the entry [name]. */
    fun file(name: String, input: InputStream) {
        val destination = destination(name) ?: return
        val existed = Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)

        if (!existed) {
            createdFiles += destination
        }

        try {
            open(destination).use { raw -> copy(input, guard.wrap(raw), BUFFER_SIZE) }
        } catch (exception: Throwable) {
            if (existed) {
                destination.delete()
            }

            throw exception
        }
    }

    /** Moves the regular file [source] to the entry [name]; a copy through the guard when a rename cannot. */
    fun place(name: String, source: File) {
        val destination = destination(name) ?: return
        val existed = Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)

        if (!existed) {
            createdFiles += destination
        }

        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)

            return
        } catch (_: IOException) {
            // Another file system, or a destination that cannot be replaced by rename: copy.
        }

        try {
            source.inputStream().use { input ->
                open(destination).use { raw -> copy(input, guard.wrap(raw), BUFFER_SIZE) }
            }
        } catch (exception: Throwable) {
            if (existed) {
                destination.delete()
            }

            throw exception
        }
    }

    /** Where a file entry goes, with its parents created, or null when it is skipped. */
    private fun destination(name: String): File? {
        val destination = resolve(name)

        if (destination.toPath().normalize() == target.toPath().normalize()) {
            return null
        }

        if (skip(ZipTool.relativeName(root, destination))) {
            return null
        }

        destination.parentFile?.let { ensureDirectory(it) }

        return destination
    }

    private fun open(file: File): FileOutputStream = try {
        FileOutputStream(file)
    } catch (exception: IOException) {
        throw WriteFailure(exception)
    }

    private fun resolve(name: String): File = try {
        PathSafety.resolveRelative(target, name)
    } catch (exception: IllegalArgumentException) {
        throw EntryRefused(exception)
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) {
            return
        }

        val missing = ArrayList<File>()
        var candidate: File? = directory.absoluteFile

        while (candidate != null && !candidate.exists()) {
            missing += candidate
            candidate = candidate.parentFile
        }

        if (!directory.mkdirs() && !directory.isDirectory) {
            throw WriteFailure(IOException("Could not create the directory ${directory.name}."))
        }

        createdDirectories += missing.asReversed()
    }

    private fun cleanup() {
        createdFiles.asReversed().forEach { file ->
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: Exception) {
            }
        }

        // Deepest first; a directory that still holds something not ours is left alone.
        createdDirectories.asReversed().forEach { directory -> directory.delete() }
    }

    /** A path check that refused an entry; passed through as the [IllegalArgumentException] it is. */
    class EntryRefused(cause: IllegalArgumentException) : IllegalArgumentException(cause.message, cause)

    /** An IOException while writing an extracted file, as opposed to one while reading the archive. */
    class WriteFailure(cause: IOException) : IOException(cause.message, cause)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** Copies until [input] ends; tags write errors so [translate] can tell them apart. */
        fun copy(input: InputStream, output: java.io.OutputStream, bufferSize: Int): Long {
            val buffer = ByteArray(bufferSize)
            var total = 0L

            while (true) {
                val read = input.read(buffer)

                if (read < 0) {
                    break
                }

                try {
                    output.write(buffer, 0, read)
                } catch (exception: IOException) {
                    throw WriteFailure(exception)
                }

                total += read
            }

            try {
                output.flush()
            } catch (exception: IOException) {
                throw WriteFailure(exception)
            }

            return total
        }

        /** What a failure means to the caller: disk full, corrupt input, or the original error. */
        fun translate(exception: Throwable, guard: DiskGuard): Throwable {
            val source = generateSequence(exception) { it.cause }.firstOrNull { it is ArchiveExtractor.SourceFailure }

            return when {
                exception is ArchiveException -> exception
                exception is EntryRefused -> exception
                exception is Error -> exception
                exception is InterruptedException -> exception
                source != null -> source.cause ?: source
                exception is WriteFailure -> if (guard.isLow()) NotEnoughSpaceException(null, guard.free()) else exception.cause ?: exception
                exception is java.io.InterruptedIOException -> exception
                // Includes the errors commons-compress raises as runtime exceptions on bad input.
                exception is IOException || exception is RuntimeException -> if (guard.isLow()) {
                    NotEnoughSpaceException(null, guard.free())
                } else {
                    ArchiveException(
                        ArchiveException.CORRUPT_ARCHIVE,
                        "The archive is damaged or incomplete: ${exception.message ?: exception.javaClass.simpleName}",
                        exception
                    )
                }
                else -> exception
            }
        }
    }
}
