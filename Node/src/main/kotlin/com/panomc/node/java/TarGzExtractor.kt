package com.panomc.node.java

import com.panomc.node.util.PathSafety
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * Unpacks a `.tar.gz` with nothing but the JDK (SM-63, §2.4.28).
 *
 * Both JRE sources ship Linux and macOS builds as gzipped tarballs, the JDK has no tar reader,
 * and neither a new dependency nor the host's own `tar` (absent on minimal images, different on
 * every OS) is worth it for a format this small: 512-byte headers, file data padded to 512, two
 * empty blocks at the end.
 *
 * What is understood, because real JRE archives use it: POSIX ustar headers with the `prefix`
 * field, GNU `L`/`K` long-name and long-link records, pax `x` (per entry) and `g` (global)
 * extended headers for `path`, `linkpath` and `size`, regular files, directories, symbolic links
 * and hard links. Everything else (devices, FIFOs) is skipped: a runtime never contains one, and
 * creating one would need privileges this daemon should not have.
 *
 * What is refused, because an archive is data from the internet even when its checksum matched:
 * an absolute path, a `..` segment anywhere, and a symbolic link whose target resolves outside the
 * directory being extracted into. Every entry path is resolved through [PathSafety], which also
 * catches writing *through* a symlink that an earlier entry created.
 *
 * Unix modes are applied where the file system has POSIX permissions, with the owner's read and
 * write bits always kept so the node can update or delete the runtime later; directories get
 * theirs at the very end, so a read-only directory does not stop its own children being written.
 */
object TarGzExtractor {
    private const val BLOCK = 512

    /** Largest pax or GNU long-name record believed; real ones are a few hundred bytes. */
    private const val MAX_META_BYTES = 1L shl 20

    private data class Header(
        val name: String,
        val mode: Int?,
        val size: Long,
        val type: Char,
        val linkName: String
    )

    /** Extracts the gzipped tarball [archive] into [target], which is created when missing. */
    fun extract(archive: File, target: File) {
        archive.inputStream().buffered().use { raw ->
            GZIPInputStream(raw, 1 shl 16).use { extract(it, target) }
        }
    }

    /** Extracts an uncompressed tar stream into [target]. Split out so tests can feed plain tar. */
    fun extract(input: InputStream, target: File) {
        target.mkdirs()

        val root = target.toPath().toAbsolutePath().normalize()
        val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        val directoryModes = LinkedHashMap<Path, Int>()

        val globalPax = HashMap<String, String>()
        var pendingPax: Map<String, String> = emptyMap()
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        val block = ByteArray(BLOCK)

        while (true) {
            if (!readBlock(input, block)) {
                // A tarball that ends without its two empty blocks is common enough (and harmless
                // once every entry has been read) that only a truncated entry is an error.
                break
            }

            if (block.all { it == 0.toByte() }) {
                break
            }

            val header = parseHeader(block)

            when (header.type) {
                'L' -> {
                    pendingLongName = readString(input, header.size)

                    continue
                }

                'K' -> {
                    pendingLongLink = readString(input, header.size)

                    continue
                }

                'x' -> {
                    pendingPax = parsePax(readBytes(input, header.size))

                    continue
                }

                'g' -> {
                    globalPax.putAll(parsePax(readBytes(input, header.size)))

                    continue
                }
            }

            val pax = globalPax + pendingPax

            val name = pax["path"] ?: pendingLongName ?: header.name
            val linkName = pax["linkpath"] ?: pendingLongLink ?: header.linkName
            val size = pax["size"]?.toLongOrNull() ?: header.size

            pendingPax = emptyMap()
            pendingLongName = null
            pendingLongLink = null

            val relative = entryPath(name)

            if (relative == null) {
                // `./` itself, the archive's own root: nothing to create.
                skip(input, size)

                continue
            }

            val destination = PathSafety.resolveRelative(target, relative).toPath()

            when (header.type) {
                '5' -> {
                    Files.createDirectories(destination)

                    header.mode?.let { directoryModes[destination] = it }

                    skip(input, size)
                }

                '2' -> {
                    skip(input, size)

                    createSymlink(root, destination, linkName)
                }

                '1' -> {
                    skip(input, size)

                    val sourceRelative = entryPath(linkName)
                        ?: throw IllegalStateException("Refused a hard link to the archive root.")

                    val source = PathSafety.resolveRelative(target, sourceRelative).toPath()

                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw IllegalStateException("Hard link $relative points at $linkName, which was not extracted.")
                    }

                    Files.createDirectories(destination.parent)
                    Files.deleteIfExists(destination)

                    // A copy rather than a link: the runtime is later moved and deleted as a
                    // whole, and a copy is the one form that behaves identically everywhere.
                    Files.copy(source, destination)

                    applyMode(destination, header.mode, posix)
                }

                '0', '\u0000', '7' -> {
                    Files.createDirectories(destination.parent)
                    Files.deleteIfExists(destination)

                    Files.newOutputStream(destination).use { output -> copyExactly(input, output, size) }

                    skipPadding(input, size)

                    applyMode(destination, header.mode, posix)
                }

                else -> skip(input, size)
            }
        }

        if (posix) {
            // Deepest first, so taking the write bit off a parent never blocks fixing a child.
            directoryModes.entries.sortedByDescending { it.key.nameCount }.forEach { (path, mode) ->
                applyMode(path, mode or OWNER_RWX, posix = true)
            }
        }
    }

    /**
     * The path an entry name stands for, relative to the extraction root, or null for the root.
     *
     * Rejects what [PathSafety.resolveRelative] would otherwise quietly accept: a leading slash or
     * drive letter is an absolute path and never "the same path, relative", and a `..` segment is
     * refused here with the entry's name in the message.
     */
    fun entryPath(name: String): String? {
        val normalised = name.replace('\\', '/')

        if (normalised.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalised)) {
            throw IllegalStateException("Refused absolute path \"$name\" in the archive.")
        }

        val segments = normalised.split('/').filter { it.isNotEmpty() && it != "." }

        if (segments.any { it == ".." }) {
            throw IllegalStateException("Refused path \"$name\" climbing out of the archive.")
        }

        return segments.joinToString("/").ifEmpty { null }
    }

    private fun createSymlink(root: Path, destination: Path, linkName: String) {
        if (linkName.isBlank()) {
            throw IllegalStateException("Refused a symbolic link with no target.")
        }

        if (linkName.startsWith("/") || linkName.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(linkName)) {
            throw IllegalStateException("Refused symbolic link to absolute path \"$linkName\".")
        }

        val resolved = destination.parent.resolve(linkName.replace('\\', '/')).normalize()

        if (!resolved.startsWith(root)) {
            throw IllegalStateException("Refused symbolic link \"$linkName\" escaping the runtime directory.")
        }

        Files.createDirectories(destination.parent)
        Files.deleteIfExists(destination)

        try {
            Files.createSymbolicLink(destination, destination.parent.relativize(resolved))
        } catch (exception: UnsupportedOperationException) {
            copyInstead(resolved, destination)
        } catch (exception: java.nio.file.FileSystemException) {
            // Windows without the symlink privilege: a copy of what it points at is what the JRE
            // needs, and a runtime with a missing file is worse than one with a duplicate.
            copyInstead(resolved, destination)
        }
    }

    private fun copyInstead(source: Path, destination: Path) {
        if (Files.isRegularFile(source)) {
            Files.copy(source, destination)
        }
    }

    private fun applyMode(path: Path, mode: Int?, posix: Boolean) {
        if (!posix || mode == null) {
            return
        }

        val permissions = HashSet<PosixFilePermission>()

        val effective = mode or OWNER_RW

        PERMISSION_BITS.forEach { (bit, permission) ->
            if (effective and bit != 0) {
                permissions.add(permission)
            }
        }

        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: Exception) {
            // A mode that cannot be set is not a reason to fail the install; the executables are
            // made executable again explicitly afterwards.
        }
    }

    private fun parseHeader(block: ByteArray): Header {
        if (!checksumMatches(block)) {
            throw IllegalStateException("The archive is not a tar file (bad header checksum).")
        }

        val name = string(block, 0, 100)
        val mode = octal(block, 100, 8)?.toInt()
        val size = number(block, 124, 12) ?: 0L
        val type = block[156].toInt().toChar()
        val linkName = string(block, 157, 100)

        val magic = string(block, 257, 6)
        val prefix = if (magic.startsWith("ustar")) string(block, 345, 155) else ""

        // GNU tar writes "ustar  " and uses the prefix bytes for other things; only a POSIX
        // ("ustar\0") header's prefix is part of the name.
        val usePrefix = prefix.isNotEmpty() && block[262] == 0.toByte()

        return Header(
            name = if (usePrefix) "$prefix/$name" else name,
            mode = mode,
            size = size,
            type = type,
            linkName = linkName
        )
    }

    private fun checksumMatches(block: ByteArray): Boolean {
        val stored = octal(block, 148, 8) ?: return false

        var unsigned = 0L
        var signed = 0L

        for (index in block.indices) {
            val value = if (index in 148 until 156) ' '.code.toByte() else block[index]

            unsigned += value.toInt() and 0xFF
            signed += value.toInt()
        }

        return stored == unsigned || stored == signed
    }

    private fun string(block: ByteArray, offset: Int, length: Int): String {
        var end = offset

        while (end < offset + length && block[end] != 0.toByte()) {
            end++
        }

        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(block: ByteArray, offset: Int, length: Int): Long? {
        val text = string(block, offset, length).trim { it == ' ' || it == '\u0000' }

        if (text.isEmpty()) {
            return null
        }

        return text.toLongOrNull(8)
    }

    /** An octal field, or GNU's base-256 form (high bit set) for sizes past 8 GiB. */
    private fun number(block: ByteArray, offset: Int, length: Int): Long? {
        if (block[offset].toInt() and 0x80 != 0) {
            var value = (block[offset].toInt() and 0x7F).toLong()

            for (index in offset + 1 until offset + length) {
                value = (value shl 8) or (block[index].toLong() and 0xFF)
            }

            return value
        }

        return octal(block, offset, length)
    }

    /**
     * Reads a pax extended header: records of `<length> <key>=<value>\n`, where the length counts
     * the whole record including itself and the newline.
     */
    fun parsePax(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()

        var position = 0

        while (position < data.size) {
            var space = position

            while (space < data.size && data[space] != ' '.code.toByte()) {
                space++
            }

            val length = String(data, position, space - position, Charsets.US_ASCII).toIntOrNull()
                ?: break

            if (length <= 0 || position + length > data.size) {
                break
            }

            val record = String(data, space + 1, position + length - space - 1, Charsets.UTF_8).removeSuffix("\n")

            val equals = record.indexOf('=')

            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }

            position += length
        }

        return result
    }

    private fun readString(input: InputStream, size: Long): String =
        String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000')

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_META_BYTES) {
            throw IllegalStateException("The archive carries an implausible $size-byte header record.")
        }

        val data = ByteArray(size.toInt())

        readFully(input, data, data.size)

        skipPadding(input, size)

        return data
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)

        var remaining = size

        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())

            if (read < 0) {
                throw EOFException("The archive ended in the middle of a file.")
            }

            output.write(buffer, 0, read)

            remaining -= read
        }
    }

    private fun skip(input: InputStream, size: Long) {
        discard(input, size)

        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK - (size % BLOCK)) % BLOCK

        discard(input, padding)
    }

    private fun discard(input: InputStream, count: Long) {
        val buffer = ByteArray(8192)

        var remaining = count

        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())

            if (read < 0) {
                throw EOFException("The archive ended early.")
            }

            remaining -= read
        }
    }

    /** Reads one whole block; false at a clean end of stream, an error in the middle of one. */
    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0

        while (read < block.size) {
            val count = input.read(block, read, block.size - read)

            if (count < 0) {
                if (read == 0) {
                    return false
                }

                throw EOFException("The archive ended in the middle of a header.")
            }

            read += count
        }

        return true
    }

    private fun readFully(input: InputStream, data: ByteArray, length: Int) {
        var read = 0

        while (read < length) {
            val count = input.read(data, read, length - read)

            if (count < 0) {
                throw EOFException("The archive ended early.")
            }

            read += count
        }
    }

    private const val OWNER_RW = 0b110_000_000
    private const val OWNER_RWX = 0b111_000_000

    private val PERMISSION_BITS = listOf(
        0b100_000_000 to PosixFilePermission.OWNER_READ,
        0b010_000_000 to PosixFilePermission.OWNER_WRITE,
        0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
        0b000_100_000 to PosixFilePermission.GROUP_READ,
        0b000_010_000 to PosixFilePermission.GROUP_WRITE,
        0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
        0b000_000_100 to PosixFilePermission.OTHERS_READ,
        0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
        0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE
    )
}
