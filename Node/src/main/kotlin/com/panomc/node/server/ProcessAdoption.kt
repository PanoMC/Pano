package com.panomc.node.server

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Whether the process a [ProcessRecord] names is still the process it was written for (SM-51).
 *
 * The decision is pure and lives here rather than inside the runtime because it is the part that
 * can be wrong in an expensive way. A record is a pid and a number, a pid is reused, and adopting
 * the wrong one would have the node report somebody else's process as a Minecraft server and then
 * offer a button that signals it. Three independent facts have to agree before that happens.
 */
object ProcessAdoption {
    /**
     * How far the process's own start time may be from the recorded one.
     *
     * The record is written milliseconds after the spawn, so this is slack for a clock and for the
     * granularity of what the OS reports, not a window in which two processes could be confused:
     * a reused pid is minutes or hours later, never five seconds.
     */
    const val START_TOLERANCE_MILLIS = 5_000L

    /**
     * The three checks, in the order of how much they prove.
     *
     * - A dead pid is the end of it: the server exited while nothing was watching.
     * - A command line that does not mention the recorded jar belongs to something else, whatever
     *   its start time says.
     * - The start time is the pid-reuse guard, and it is the one that actually settles the
     *   question. Some JVMs and some hosts cannot report it at all ([startedAt] null), and then
     *   the working directory stands in for it: a process running *inside this server's
     *   directory* with this server's jar on its command line is this server. With neither, the
     *   command line alone is all there is, which is the weakest honest answer rather than a
     *   refusal to adopt anything on a platform that cannot introspect processes.
     */
    fun matches(
        record: ProcessRecord,
        alive: Boolean,
        startedAt: Long?,
        commandLine: String?,
        workingDirectory: String?,
        serverDirectory: String
    ): Boolean {
        if (!alive) {
            return false
        }

        if (commandLine != null && !commandLine.contains(record.command)) {
            return false
        }

        if (startedAt != null) {
            return abs(startedAt - record.startedAt) <= START_TOLERANCE_MILLIS
        }

        if (workingDirectory != null) {
            return workingDirectory == serverDirectory
        }

        return commandLine != null
    }

    /** The live handle [record] names, or null when it is not the process it was written for. */
    fun handleFor(record: ProcessRecord, serverDirectory: File): ProcessHandle? {
        val handle = ProcessHandle.of(record.pid).orElse(null) ?: return null

        val adopted = matches(
            record = record,
            alive = handle.isAlive,
            startedAt = startedAtOf(handle),
            commandLine = commandLineOf(handle),
            workingDirectory = workingDirectoryOf(record.pid),
            serverDirectory = canonical(serverDirectory)
        )

        return handle.takeIf { adopted }
    }

    /** When the OS says the process started, or null where it will not say. */
    fun startedAtOf(handle: ProcessHandle): Long? =
        try {
            handle.info().startInstant().orElse(null)?.toEpochMilli()
        } catch (_: Exception) {
            null
        }

    /**
     * The process's command line, rebuilt from its parts when the JVM will not hand one over.
     *
     * `commandLine()` is empty unless both the command and its arguments are readable, which on
     * some platforms they are not together even though they are separately.
     */
    fun commandLineOf(handle: ProcessHandle): String? = try {
        val info = handle.info()
        val whole = info.commandLine().orElse(null)

        if (!whole.isNullOrBlank()) {
            whole
        } else {
            val command = info.command().orElse(null)
            val arguments = info.arguments().orElse(emptyArray()).joinToString(" ")

            listOfNotNull(command, arguments.takeIf { it.isNotBlank() })
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The directory a process is running in, on the systems that expose one.
     *
     * `/proc/<pid>/cwd` is a Linux thing and deliberately not emulated elsewhere: null means "this
     * host cannot answer", which [matches] treats as one fewer fact rather than as a mismatch.
     */
    fun workingDirectoryOf(pid: Long): String? = try {
        val path = Path.of("/proc", pid.toString(), "cwd")

        if (Files.exists(path)) path.toRealPath().toString() else null
    } catch (_: Exception) {
        null
    }

    private fun canonical(directory: File): String = try {
        directory.canonicalPath
    } catch (_: Exception) {
        directory.absolutePath
    }
}
