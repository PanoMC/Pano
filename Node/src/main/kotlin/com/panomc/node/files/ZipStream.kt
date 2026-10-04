package com.panomc.node.files

import com.panomc.node.util.PathSafety
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a selection of server files as one zip, straight into whatever stream is reading it.
 *
 * The file manager's "download these" has no archive on disk to point at, and building one first
 * would mean a second copy of a world the size of the disk it is on. So the archive is produced
 * as it is sent: [write] walks the selection and hands every entry to a [ZipOutputStream] wrapped
 * around the caller's stream, which during a transfer is the pipe feeding the PUT to Pano.
 *
 * What goes in follows the same rules as everything else the file manager touches. A denied file
 * found while walking a directory is skipped rather than refused — zipping `plugins` has to work,
 * it simply leaves this server's credentials out — and symbolic links are skipped outright, since
 * following one is how a directory inside the server ends up shipping a file from outside it.
 */
object ZipStream {
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Zips [paths] into [output], naming every entry relative to [base], and returns the bytes read.
     *
     * [paths] and [base] are server-relative and must already have been checked by the caller:
     * this is the part that writes, not the part that decides whether the selection is allowed.
     * Directories go in recursively; one that ends up with nothing in it still gets its own
     * `name/` entry, so an empty folder survives the round trip.
     *
     * Throws once more than [maxBytes] of file content or more than [ZipTool.MAX_ENTRIES] entries
     * would have been written. The zip is never finished in that case, which is the point — the
     * reader must see a broken stream, not a smaller archive that looks complete.
     *
     * [output] is finished as a zip but not closed; closing it is the caller's call.
     */
    fun write(
        serverDirectory: File,
        base: String,
        paths: List<String>,
        output: OutputStream,
        maxBytes: Long = TransferService.MAX_TRANSFER_BYTES
    ): Long {
        val normalisedBase = ServerFileDenylist.normalise(base)
        val state = State(maxBytes)
        val zip = ZipOutputStream(output)

        paths.map { ServerFileDenylist.normalise(it) }.distinct().forEach { path ->
            val source = PathSafety.resolveRelative(serverDirectory, path)

            add(zip, source, path, entryName(normalisedBase, path), state)
        }

        zip.finish()
        zip.flush()

        return state.bytes
    }

    /** [path] as an entry name: relative to [base], or the whole path when [base] is the root. */
    fun entryName(base: String, path: String): String = when {
        base.isEmpty() -> path
        path == base -> ""
        else -> path.removePrefix("$base/")
    }

    /**
     * Adds [file] under [name], and everything under it when it is a directory.
     *
     * Returns whether anything was written, so a directory whose every child was skipped can
     * still be recorded as the empty folder it now is inside the archive.
     */
    private fun add(zip: ZipOutputStream, file: File, path: String, name: String, state: State): Boolean {
        val filePath = file.toPath()

        if (Files.isSymbolicLink(filePath)) {
            return false
        }

        if (Files.isDirectory(filePath, LinkOption.NOFOLLOW_LINKS)) {
            var wroteChild = false

            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"

                if (ServerFileDenylist.isDenied(childPath)) {
                    return@forEach
                }

                val childName = if (name.isEmpty()) child.name else "$name/${child.name}"

                if (add(zip, child, childPath, childName, state)) {
                    wroteChild = true
                }
            }

            // The base directory itself has no name inside its own archive.
            if (!wroteChild && name.isNotEmpty()) {
                putEntry(zip, file, "$name/", state)
                zip.closeEntry()

                return true
            }

            return wroteChild
        }

        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            // A socket, a device or a file that vanished mid-walk: nothing a download can carry.
            return false
        }

        putEntry(zip, file, name, state)

        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)

            while (true) {
                val read = input.read(buffer)

                if (read <= 0) {
                    break
                }

                state.bytes += read

                if (state.bytes > state.maxBytes) {
                    throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                }

                zip.write(buffer, 0, read)
            }
        }

        zip.closeEntry()

        return true
    }

    private fun putEntry(zip: ZipOutputStream, file: File, name: String, state: State) {
        if (++state.entries > ZipTool.MAX_ENTRIES) {
            throw IllegalStateException("This selection holds more than ${ZipTool.MAX_ENTRIES} files.")
        }

        val entry = ZipEntry(name)

        runCatching {
            entry.lastModifiedTime = FileTime.fromMillis(file.lastModified())
        }

        zip.putNextEntry(entry)
    }

    private class State(val maxBytes: Long) {
        var bytes = 0L
        var entries = 0
    }
}
