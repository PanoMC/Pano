package com.panomc.node.files

import com.panomc.node.agent.AgentFiles
import com.panomc.node.util.PathSafety
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Zip and unzip inside a server directory, with the two things an archive tool has to get right.
 *
 * **Zip slip**: an entry name is attacker-controlled text, not a path, and `../../etc/cron.d/x`
 * inside a zip is a file write outside the extraction directory on every naive implementation.
 * Every entry goes through [PathSafety.resolveRelative] against the extraction root, which rejects
 * it before anything is opened.
 *
 * **Zip bombs**: a few hundred kilobytes can declare terabytes of output, so extraction stops at a
 * total size and an entry count rather than at whatever the disk can take.
 */
object ZipTool {
    /** Most bytes a single extract may write, across all entries. */
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    /** Most entries a single archive may hold, in either direction. */
    const val MAX_ENTRIES = 100_000

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Zips [paths] (relative to [root]) into [target].
     *
     * Directories go in whole. Denied files are skipped rather than refused, so archiving
     * `plugins` still works and simply produces an archive without this server's credentials in
     * it — which is also what makes the same code safe to reuse for backups.
     */
    fun archive(root: File, paths: List<String>, target: File, exclude: (String) -> Boolean = { false }): Long {
        target.parentFile?.mkdirs()

        var entries = 0
        var written = 0L

        ZipOutputStream(target.outputStream().buffered()).use { out ->
            paths.forEach { path ->
                val source = PathSafety.resolveRelative(root, path)
                val base = ServerFileDenylist.normalise(path)

                if (!source.exists()) {
                    return@forEach
                }

                source.walkTopDown().forEach inner@{ file ->
                    val relative = relativeName(root, file)

                    if (relative.isEmpty() || ServerFileDenylist.isDenied(relative) || exclude(relative)) {
                        return@inner
                    }

                    if (!relative.startsWith(base)) {
                        return@inner
                    }

                    if (++entries > MAX_ENTRIES) {
                        throw IllegalStateException("This selection holds more than $MAX_ENTRIES files.")
                    }

                    if (file.isDirectory) {
                        out.putNextEntry(ZipEntry("$relative/"))
                        out.closeEntry()

                        return@inner
                    }

                    out.putNextEntry(ZipEntry(relative))

                    written += copy(file.inputStream().buffered(), out)

                    out.closeEntry()
                }
            }
        }

        return written
    }

    /**
     * Extracts [archive] into [targetDirectory], which must be inside [root].
     *
     * Symlink entries are not recreated: a zip can declare one pointing anywhere, and following it
     * on the next write would put a file outside the sandbox that every path check had already
     * approved.
     */
    fun extract(root: File, archive: File, targetDirectory: File) {
        require(archive.isFile) { "The archive does not exist." }

        targetDirectory.mkdirs()

        var entries = 0
        var written = 0L

        ZipFile(archive).use { zip ->
            val iterator = zip.entries()

            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()

                if (++entries > MAX_ENTRIES) {
                    throw IllegalStateException("This archive holds more than $MAX_ENTRIES entries.")
                }

                // Resolved against the extraction directory, which is itself already inside the
                // server: an entry that climbs out of either is refused here, before the stream is
                // opened.
                val destination = PathSafety.resolveRelative(targetDirectory, entry.name)

                if (entry.isDirectory) {
                    destination.mkdirs()

                    continue
                }

                val relative = relativeName(root, destination)

                // Nor over a Pano Agent's own jar (SM-74): the agent runs from it, and an archive
                // that happens to carry an older one must not replace it.
                if (ServerFileDenylist.isDenied(relative) || AgentFiles.isOwnJar(root, relative)) {
                    continue
                }

                destination.parentFile?.mkdirs()

                zip.getInputStream(entry).use { input ->
                    destination.outputStream().buffered().use { output ->
                        written += copy(input, output)
                    }
                }

                if (written > MAX_TOTAL_BYTES) {
                    throw IllegalStateException("This archive unpacks to more than $MAX_TOTAL_BYTES bytes.")
                }
            }
        }
    }

    /**
     * Whether [file] starts with a zip local-file header.
     *
     * The same check the downloader makes, for the same reason: an HTML error page saved under a
     * `.zip` name should fail as "this is not an archive", not deep inside the zip reader.
     */
    fun isZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) {
            return false
        }

        return file.inputStream().use { input ->
            val header = ByteArray(4)

            if (input.read(header) != 4) {
                return@use false
            }

            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    }

    /**
     * Lifts the contents of a lone top-level directory up into [directory].
     *
     * Almost every archive a person makes of a server is `MyServer/server.jar`, not `server.jar`:
     * zipping a folder puts the folder in the zip. Extracting that as-is produces a server
     * directory whose only content is another directory, which has no jar, no server.properties
     * and no way to start. Flattening is what makes "zip your server folder and upload it" mean
     * what everybody assumes it means.
     *
     * Only ever one level, and only when that level is the *only* thing there: an archive holding
     * a jar next to a folder is already a server directory and must be left exactly as it is.
     * Reports whether anything moved.
     */
    fun flattenSingleRoot(directory: File): Boolean {
        val entries = directory.listFiles()?.filterNot { it.name == "__MACOSX" } ?: return false

        val root = entries.singleOrNull()?.takeIf { it.isDirectory } ?: return false

        val children = root.listFiles() ?: return false

        children.forEach { child ->
            val target = File(directory, child.name)

            if (!child.renameTo(target)) {
                if (child.isDirectory) {
                    child.copyRecursively(target, overwrite = true)
                } else {
                    child.copyTo(target, overwrite = true)
                }

                child.deleteRecursively()
            }
        }

        root.deleteRecursively()

        return true
    }

    /** The path of [file] relative to [root], with forward slashes and no leading one. */
    fun relativeName(root: File, file: File): String {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val filePath = file.toPath().toAbsolutePath().normalize()

        if (!filePath.startsWith(rootPath)) {
            return ""
        }

        return rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
    }

    private fun copy(input: InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)

                if (read <= 0) {
                    break
                }

                output.write(buffer, 0, read)

                total += read
            }
        }

        return total
    }
}
