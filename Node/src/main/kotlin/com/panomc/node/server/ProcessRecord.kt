package com.panomc.node.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * What the daemon wrote down about the process it started for one server (SM-51, §2.4.16).
 *
 * The record exists because a daemon restart used to lose every server it was supervising: the
 * JVMs carried on running -- nothing kills a child when its parent exits -- but the new daemon
 * held no handles, reported STOPPED, and the panel offered a Start button for a server that was
 * full of players. A file in the server directory is the only place that survives the restart, so
 * it is where the ownership is written.
 *
 * [command] is the jar name rather than the whole command line on purpose: it is what the
 * adoption check looks for in the process's arguments, and a full command line would also contain
 * the java path and heap flags, which an operator may legitimately have changed between the two
 * daemons without the process having been replaced.
 */
data class ProcessRecord(
    /** Process id at the time of the spawn. Meaningless on its own -- see [startedAt]. */
    val pid: Long,
    /**
     * Epoch millis the process started, read from the handle where the JVM can tell.
     *
     * The pid-reuse guard: a pid is a small number a busy host hands out again within hours, and
     * adopting whatever now holds it would mean the node reporting a stranger's process as this
     * server and, worse, offering a Stop button that signals it.
     */
    val startedAt: Long,
    /** The jar the process was launched with, which is what its command line has to contain. */
    val command: String,
    /** [ProcessRuntime.ID] or [DockerRuntime.ID]. */
    val runtime: String,
    /** Container name, for a Docker server: the container *is* the record there. */
    val container: String? = null,
    /**
     * [IO_DETACHED] when the server was started to be re-attached to (SM-62, §2.4.27): its input,
     * output and exit do not depend on the daemon that started it, so the next daemon takes it back
     * with full control. Null for a record written before that existed, which keeps the limited
     * adoption of SM-51.
     */
    val io: String? = null,
    /** The launcher's pid (process runtime), which is what exits last and leaves the exit code. */
    val launcherPid: Long? = null,
    /** The JVM's pid (process runtime); [pid] names the same process. */
    val javaPid: Long? = null,
    /** Bytes of `console.out` a daemon has delivered, so the next one neither repeats nor skips. */
    val outOffset: Long? = null,
    /**
     * Docker: epoch nanos of the last output line a daemon saw, which is where `docker logs --since`
     * picks up after a re-attach.
     */
    val logsSince: Long? = null,
    /**
     * The Java home the server was launched from (SM-63), so a daemon that re-attaches still knows
     * which runtime must not be removed while the server runs. Null in records written before.
     */
    val javaHome: String? = null
) {
    val isDetached: Boolean get() = io == IO_DETACHED

    companion object {
        const val IO_DETACHED = "detached"
    }
}

/** Reads and writes `<server dir>/.pano-node/process.json`. */
object ProcessRecordStore {
    /** The node's own folder inside a server directory. Hidden from the file manager. */
    const val DIRECTORY = ".pano-node"

    const val FILE = "process.json"

    fun directory(serverDirectory: File) = File(serverDirectory, DIRECTORY)

    fun file(serverDirectory: File) = File(directory(serverDirectory), FILE)

    /**
     * Writes the record, or quietly gives up.
     *
     * Never throws: this runs immediately after a server was started, and a read-only directory
     * is a reason to lose adoption across the next restart, not a reason to fail the start that
     * has already happened.
     */
    fun write(serverDirectory: File, record: ProcessRecord): Boolean = try {
        directory(serverDirectory).mkdirs()

        file(serverDirectory).writeText(gson.toJson(record))

        true
    } catch (_: Exception) {
        false
    }

    /** The record, or null when there is none, it cannot be read, or it says nothing usable. */
    fun read(serverDirectory: File): ProcessRecord? {
        val file = file(serverDirectory)

        if (!file.isFile) {
            return null
        }

        val record = try {
            gson.fromJson(file.readText(), ProcessRecord::class.java)
        } catch (_: Exception) {
            null
        } ?: return null

        if (record.pid <= 0 || record.startedAt <= 0 || record.command.isBlank() || record.runtime.isBlank()) {
            return null
        }

        return record
    }

    /** Removes the record. Only ever called for an exit this daemon actually saw. */
    fun delete(serverDirectory: File) {
        try {
            file(serverDirectory).delete()
        } catch (_: Exception) {
            // A record that cannot be removed is re-checked on the next start and found stale
            // there, which is the same outcome one restart later.
        }
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
}
