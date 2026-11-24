package com.panomc.node.archive

import com.panomc.node.util.SafeDelete
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * RAR, unpacked by an external tool in a child process.
 *
 * Never in the JVM: the UnRAR source licence forbids using it to build a RAR compressor and is
 * not GPL-compatible, Pano is GPLv3, and a crash in a native decoder must not be able to take the
 * node down with it. 7-Zip, unrar or libarchive's bsdtar (which Windows ships as `tar.exe`) do the
 * decoding instead.
 *
 * The tool never writes into the server: it unpacks into a fresh private staging directory next
 * to the target, and the node then walks that directory (never following links, dropping anything
 * that is not a regular file or directory) and moves each file in through the same
 * [ExtractionSession] rules every other format uses. While the tool runs, the disk is checked every
 * second and the tool is killed when it drops below the reserve; a tool that stops producing output
 * for [STALL_TIMEOUT_MILLIS] is killed as hung. Neither the child nor the staging directory ever
 * outlives [extract].
 */
internal object RarExtractor {
    const val STALL_TIMEOUT_MILLIS = 10L * 60 * 1000

    private const val POLL_MILLIS = 1000L

    /** How often the staging directory is measured for the stall check (walking it is not free). */
    private const val MEASURE_EVERY_MILLIS = 15_000L

    class Tool(
        val name: String,
        val executable: File,
        val arguments: (archive: File, staging: File) -> List<String>,
        /** 7-Zip and unrar use 1 for "warnings, but extracted". */
        val okExitCodes: Set<Int> = setOf(0)
    )

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The RAR-capable tools on this machine, in the order they are tried. */
    fun installed(): List<Tool> {
        val tools = ArrayList<Tool>()

        listOf("7zz", "7z", "7za").forEach { name ->
            val executable = onPath(name) ?: if (name == "7z" && isWindows) programFiles7z() else null

            executable?.let { tools += sevenZip(name, it) }
        }

        onPath("unrar")?.let { tools += unrar(it) }
        onPath("bsdtar")?.let { tools += bsdtar("bsdtar", it) }

        if (isWindows) {
            // Windows' own tar is libarchive; PATH often has Git's GNU tar first, which cannot read RAR.
            val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"

            File(systemRoot, "System32\\tar.exe").takeIf { it.isFile }?.let { tools += bsdtar("tar.exe", it) }
        }

        return tools
    }

    fun sevenZip(name: String, executable: File) = Tool(name, executable, { archive, staging ->
        // A dummy password: an encrypted archive fails instead of prompting on a stdin nobody reads.
        listOf("x", "-y", "-bd", "-p-", "-o${staging.absolutePath}", archive.absolutePath)
    }, setOf(0, 1))

    fun unrar(executable: File) = Tool("unrar", executable, { archive, staging ->
        listOf("x", "-y", "-p-", "-idq", "-o+", archive.absolutePath, staging.absolutePath + File.separator)
    }, setOf(0, 1))

    fun bsdtar(name: String, executable: File) = Tool(name, executable, { archive, staging ->
        listOf("-x", "-f", archive.absolutePath, "-C", staging.absolutePath)
    })

    fun extract(
        archive: File,
        session: ExtractionSession,
        guard: DiskGuard,
        tools: List<Tool>,
        stallTimeoutMillis: Long = STALL_TIMEOUT_MILLIS
    ) {
        if (tools.isEmpty()) {
            throw unavailable()
        }

        val parent = session.target.parentFile ?: session.target
        parent.mkdirs()

        val staging = createStaging(parent)

        try {
            for (tool in tools) {
                val process = try {
                    ProcessBuilder(listOf(tool.executable.absolutePath) + tool.arguments(archive, staging))
                        // Not the staging directory: Windows cannot delete a directory that is a
                        // live process's working directory.
                        .directory(parent)
                        .redirectErrorStream(true)
                        .start()
                } catch (_: IOException) {
                    // Found but not runnable (wrong architecture, no permission): try the next one.
                    continue
                }

                try {
                    watch(tool, process, staging, guard, stallTimeoutMillis)
                } finally {
                    kill(process)
                }

                moveIn(staging, session)

                return
            }

            throw unavailable()
        } finally {
            SafeDelete.tree(staging)
        }
    }

    private fun watch(tool: Tool, process: Process, staging: File, guard: DiskGuard, stallTimeoutMillis: Long) {
        // Nothing reads the child's stdin; closing it turns any prompt into an immediate EOF.
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }

        val lastLine = AtomicReference<String?>(null)

        val reader = Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        lastLine.set(line.trim())
                    }
                }
            } catch (_: IOException) {
            }
        }, "pano-rar-output").apply {
            isDaemon = true
            start()
        }

        val measureEvery = minOf(MEASURE_EVERY_MILLIS, maxOf(1L, stallTimeoutMillis / 4))
        var lastSize = -1L
        var lastGrowth = System.currentTimeMillis()
        var lastMeasure = 0L

        while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            guard.check()

            val now = System.currentTimeMillis()

            if (now - lastMeasure >= measureEvery) {
                lastMeasure = now

                val size = measure(staging)

                if (size != lastSize) {
                    lastSize = size
                    lastGrowth = now
                } else if (now - lastGrowth >= stallTimeoutMillis) {
                    throw ArchiveException(
                        ArchiveException.CORRUPT_ARCHIVE,
                        "The RAR extractor (${tool.name}) stopped making progress and was stopped."
                    )
                }
            }
        }

        // The output is fully read once the process is gone; wait briefly so the last line is in.
        reader.join(2000)

        guard.check()

        val exit = process.exitValue()

        if (exit !in tool.okExitCodes) {
            val detail = lastLine.get()?.let { ": $it" }.orEmpty()

            throw ArchiveException(
                ArchiveException.CORRUPT_ARCHIVE,
                "The RAR archive could not be extracted (${tool.name} exited with $exit)$detail"
            )
        }
    }

    /** Walks [staging] without following links and moves every regular file in through [session]. */
    private fun moveIn(staging: File, session: ExtractionSession) {
        val base = staging.toPath()

        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == base) {
                    return FileVisitResult.CONTINUE
                }

                // A link or a Windows junction is never entered, and nothing behind it is taken.
                if (attrs.isSymbolicLink || attrs.isOther) {
                    return FileVisitResult.SKIP_SUBTREE
                }

                session.directory(relative(base, dir))

                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                    session.place(relative(base, file), file.toFile())
                }

                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }

    private fun relative(base: Path, path: Path): String = base.relativize(path).toString().replace(File.separatorChar, '/')

    private fun measure(staging: File): Long {
        var total = 0L

        try {
            Files.walkFileTree(staging.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Count and size both, so many small files still show as progress.
                    total += attrs.size() + 1

                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
        }

        return total
    }

    private fun kill(process: Process) {
        if (!process.isAlive) {
            return
        }

        val descendants = try {
            process.descendants().toArray { arrayOfNulls<ProcessHandle>(it) }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }

        descendants.forEach { it.destroyForcibly() }
        process.destroyForcibly()

        // A child still holding a file in staging would keep it from being deleted (Windows).
        try {
            process.waitFor(10, TimeUnit.SECONDS)

            descendants.forEach { handle ->
                try {
                    handle.onExit().get(5, TimeUnit.SECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                } catch (_: java.util.concurrent.ExecutionException) {
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun createStaging(parent: File): File {
        val parentPath = parent.toPath()

        val path = if (parentPath.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createTempDirectory(parentPath, ".pano-unrar-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createTempDirectory(parentPath, ".pano-unrar-")
        }

        return path.toFile()
    }

    private fun onPath(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        val names = if (isWindows) listOf("$name.exe", name) else listOf(name)

        return path.split(File.pathSeparatorChar).asSequence()
            .filter { it.isNotBlank() }
            .flatMap { directory -> names.asSequence().map { File(directory.trim('"'), it) } }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private fun programFiles7z(): File? = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), "C:\\Program Files")
        .map { File(it, "7-Zip\\7z.exe") }
        .firstOrNull { it.isFile }

    private fun unavailable() =
        ArchiveException(ArchiveException.RAR_UNAVAILABLE, "No RAR extractor (7-Zip, unrar or bsdtar) is installed on this node.")
}
