package com.panomc.node.server

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/**
 * Where a detached server's plumbing lives: `<server>/.pano-node/…` (SM-62, §2.4.27).
 *
 * A server started through the launcher is not tied to this daemon by a single pipe. Its stdin is
 * a FIFO the launcher holds open, its output is a file, its exit code is a file — all of it in the
 * node's own folder inside the server directory, which the file manager hides and backups skip, so
 * any number of daemons one after another can find the same server and pick it up where the last
 * one let go.
 */
class DetachedFiles(serverDirectory: File) {
    val directory = File(serverDirectory, ProcessRecordStore.DIRECTORY)

    /** The launcher script, rewritten on every start so it always matches this daemon's version. */
    val launcher = File(directory, LauncherScript.FILE)

    /** The server's stdin: a FIFO the launcher holds open read-write for as long as it runs. */
    val stdin = File(directory, "stdin")

    /** Everything the server prints, stdout and stderr together, colour codes kept. */
    val output = File(directory, OUTPUT_FILE)

    /** The previous [output] once it has been rotated. */
    val rotatedOutput = File(directory, "$OUTPUT_FILE.1")

    /** The exit code the launcher writes when the server is gone. */
    val exit = File(directory, EXIT_FILE)

    /** The launcher's own stdout and stderr — only ever written to if the launcher itself fails. */
    val launcherLog = File(directory, "launcher.log")

    companion object {
        const val OUTPUT_FILE = "console.out"
        const val EXIT_FILE = "exit"

        /** Every file name above, for the backup exclusion. */
        val RUNTIME_FILE_NAMES = listOf(LauncherScript.FILE, "stdin", OUTPUT_FILE, EXIT_FILE, "launcher.log")
    }
}

/** Reads the exit code the launcher wrote (§2.4.27). */
object ExitFile {
    /** The code in [file], or null when there is none — the launcher never got to write it. */
    fun read(file: File): Int? = try {
        if (file.isFile) file.readText().trim().toIntOrNull() else null
    } catch (_: Exception) {
        null
    }

    /** Removes the file and its temporary sibling, before a start or after an exit was booked. */
    fun clear(file: File) {
        try {
            file.delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        } catch (_: Exception) {
        }
    }
}

/**
 * Creates the FIFO a detached server reads its console input from.
 *
 * The JDK has no API for a named pipe, so this is the one place the node runs `mkfifo` — as an
 * argument list, never a shell. Returns false on any failure, and the caller then starts the server
 * the way it always did, with pipes: a host without `mkfifo` loses re-attach, not the server.
 */
object Fifo {
    private val CANDIDATES = listOf("/usr/bin/mkfifo", "/bin/mkfifo")

    fun create(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()

            Files.deleteIfExists(file.toPath())

            val command = CANDIDATES.firstOrNull { File(it).canExecute() } ?: "mkfifo"

            val process = ProcessBuilder(command, "-m", "600", file.absolutePath)
                .redirectErrorStream(true)
                .start()

            process.outputStream.close()
            process.inputStream.readAllBytes()

            val finished = process.waitFor(MKFIFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            finished && process.exitValue() == 0 && isFifo(file)
        } catch (_: Exception) {
            false
        }
    }

    /** Whether [file] exists and is neither a regular file nor a directory. */
    fun isFifo(file: File): Boolean {
        val path = file.toPath()

        return Files.exists(path) && !Files.isRegularFile(path) && !Files.isDirectory(path)
    }

    private const val MKFIFO_TIMEOUT_SECONDS = 10L
}

/** Owner-only permissions for a file the node creates, where the platform has such a thing. */
internal fun ownerOnly(file: File, executable: Boolean) {
    try {
        val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        if (executable) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
        }

        Files.setPosixFilePermissions(file.toPath(), permissions)
    } catch (_: Exception) {
        // Not a POSIX filesystem: there is nothing finer to set.
    }
}
