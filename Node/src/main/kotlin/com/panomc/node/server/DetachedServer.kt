package com.panomc.node.server

import com.panomc.node.console.ConsoleFileTailer
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * A server running under the launcher, held by whichever daemon is up right now (SM-62, §2.4.27).
 *
 * Everything a [Process] used to give the supervisor, rebuilt from things that outlive a daemon:
 * input is a write to the FIFO, output is [tailer] reading `console.out`, the exit is the launcher
 * going away and the code it left in the exit file, and a signal goes to the JVM by pid. A daemon
 * that starts the server builds one of these from the handles it just spawned; a daemon that finds
 * the server already running builds the same thing from the pids in `process.json` — and from then
 * on the two are indistinguishable, which is the point.
 *
 * [java] may be null for a moment after a start, while the launcher is still forking; signals then
 * go to the launcher, which forwards them.
 */
class DetachedServer(
    val files: DetachedFiles,
    val launcher: ProcessHandle?,
    val java: ProcessHandle?,
    val tailer: ConsoleFileTailer
) {
    private var writer: RandomAccessFile? = null

    val javaPid: Long? get() = java?.pid()

    val launcherPid: Long? get() = launcher?.pid()

    /** The process whose liveness and exit mean "the server": the launcher, or the JVM without one. */
    private val watched: ProcessHandle? get() = launcher ?: java

    fun isAlive(): Boolean = watched?.isAlive == true

    /**
     * Writes one line to the server's stdin.
     *
     * Opened read-write, like the launcher holds it: a FIFO opened only for writing blocks until a
     * reader exists, and read-write never does. The launcher is always a reader, so the line goes to
     * the server; this end never reads, so nothing is taken from it.
     */
    @Synchronized
    fun writeLine(line: String): Boolean = try {
        val out = writer ?: RandomAccessFile(files.stdin, "rw").also { writer = it }

        out.write("$line\n".toByteArray(Charsets.UTF_8))

        true
    } catch (_: Exception) {
        closeWriter()

        false
    }

    /** Whether the JVM has exited within [timeoutSeconds]. */
    fun awaitJavaExit(timeoutSeconds: Long): Boolean {
        val handle = java ?: return awaitExitOf(launcher, timeoutSeconds)

        return awaitExitOf(handle, timeoutSeconds)
    }

    /** SIGTERM to the JVM — or to the launcher, which forwards it, when the JVM is not known yet. */
    fun terminate() {
        (java ?: launcher)?.destroy()
    }

    /** SIGKILL to the JVM and anything it started; the launcher then books the exit as usual. */
    fun kill() {
        val target = java ?: launcher ?: return

        try {
            target.descendants().forEach { it.destroyForcibly() }
        } catch (_: Exception) {
        }

        target.destroyForcibly()
    }

    /**
     * Blocks until the server is gone and returns the code the launcher wrote, or null when it
     * could not write one (it was killed itself, or there never was a launcher to write it).
     */
    fun awaitExit(): Int? {
        val handle = watched ?: return ExitFile.read(files.exit)

        try {
            handle.onExit().get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()

            return null
        } catch (_: Exception) {
        }

        return ExitFile.read(files.exit)
    }

    @Synchronized
    fun closeWriter() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }

        writer = null
    }

    private fun awaitExitOf(handle: ProcessHandle?, timeoutSeconds: Long): Boolean {
        if (handle == null || !handle.isAlive) {
            return true
        }

        return try {
            handle.onExit().get(timeoutSeconds, TimeUnit.SECONDS)

            true
        } catch (_: Exception) {
            !handle.isAlive
        }
    }
}

/**
 * What a runtime hands back from a detached start: the launcher it spawned, the JVM under it (null
 * if it had not appeared yet), and where its files are.
 */
class DetachedLaunch(val launcher: Process, val java: ProcessHandle?, val files: DetachedFiles)

/**
 * What a runtime found for a server whose record says it was started to be re-attached to (SM-62).
 */
sealed interface Reattachment {
    /** Process runtime: the server is still running under its launcher. Full control resumes. */
    class Detached(
        val launcher: ProcessHandle?,
        val java: ProcessHandle,
        val files: DetachedFiles,
        val outOffset: Long
    ) : Reattachment

    /**
     * Docker: the container is still running. [attach] is a fresh `docker attach` client whose stdin
     * is the server's again; [logs] follows its output from [sinceNanos], timestamps on.
     */
    class Container(val attach: Process, val logs: Process, val sinceNanos: Long) : Reattachment

    /**
     * The server exited while no daemon was running. [exitCode] is what it left behind (null when
     * nothing recorded it), [replay] what it printed after the last line a daemon had read.
     */
    class Exited(val exitCode: Int?, val replay: List<String>) : Reattachment
}
