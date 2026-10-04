package com.panomc.platform.node

import io.vertx.core.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Deletes the local node's data directory once the node is gone (SM-64, §2.4.29 B).
 *
 * Pano created `node-data` when it set the local node up, so it is Pano's to remove when the node
 * is deleted — whether the daemon uninstalled itself first (it leaves only its retired marker) or
 * was offline and the delete was forced (then everything is still there).
 *
 * The one thing that must not happen is deleting files from under a Minecraft server that is still
 * running. Servers outlive their daemon on purpose (SM-51/SM-62), so a local node that crashed can
 * leave servers running with nobody supervising them. Those are found through the ownership record
 * each server directory holds (`.pano-node/process.json`) and stopped first; a record is only
 * trusted when the process it names is alive *and* started when the record says it did, so a pid
 * the OS has since handed to something else is never signalled.
 */
object LocalNodeDataCleanup {
    /** A server process a record points at, and whether it is a container rather than a pid. */
    data class Leftover(val serverUuid: String, val pid: Long?, val container: String?)

    /**
     * The server processes still running out of [dataDir], according to their ownership records.
     */
    fun leftoverServers(dataDir: File): List<Leftover> {
        val serversRoot = File(dataDir, "servers")

        return serversRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { directory ->
            val record = File(File(directory, RECORD_DIRECTORY), RECORD_FILE).takeIf { it.isFile } ?: return@mapNotNull null

            val json = try {
                JsonObject(record.readText())
            } catch (_: Exception) {
                return@mapNotNull null
            }

            if (json.getString("runtime").equals("DOCKER", ignoreCase = true)) {
                return@mapNotNull Leftover(directory.name, null, json.getString("container"))
            }

            val pid = json.getLong("javaPid") ?: json.getLong("pid") ?: return@mapNotNull null
            val startedAt = json.getLong("startedAt")

            if (!isSameProcess(pid, startedAt)) {
                return@mapNotNull null
            }

            Leftover(directory.name, pid, null)
        } ?: emptyList()
    }

    /**
     * Stops [leftovers] that are processes (SIGTERM, which a Minecraft server answers by saving and
     * stopping, then a kill after [graceSeconds]) and returns the ones still alive afterwards.
     * Containers are never touched here: they are returned as they are, for the manual steps.
     */
    fun stop(leftovers: List<Leftover>, graceSeconds: Long = STOP_GRACE_SECONDS): List<Leftover> {
        val handles = leftovers.mapNotNull { leftover ->
            leftover.pid?.let { pid -> ProcessHandle.of(pid).orElse(null)?.let { leftover to it } }
        }

        handles.forEach { (_, handle) -> handle.destroy() }

        handles.forEach { (_, handle) ->
            try {
                handle.onExit().get(graceSeconds, TimeUnit.SECONDS)
            } catch (_: Exception) {
                handle.destroyForcibly()

                try {
                    handle.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
        }

        return leftovers.filter { leftover ->
            leftover.container != null || (leftover.pid != null && ProcessHandle.of(leftover.pid).map { it.isAlive }.orElse(false))
        }
    }

    /**
     * Deletes [dataDir], or everything in it except the files in [keep] (the jar Pano runs the
     * local node from, should `local-node.jar-path` point inside it). Returns what could not be
     * removed; an empty list means the directory is gone (or holds nothing but [keep]).
     */
    fun delete(dataDir: File, keep: List<File> = emptyList()): List<String> {
        if (!dataDir.exists()) {
            return emptyList()
        }

        val kept = keep.map { it.absoluteFile.normalize() }
            .filter { it.toPath().startsWith(dataDir.absoluteFile.normalize().toPath()) }

        if (kept.isEmpty()) {
            return if (dataDir.deleteRecursively()) emptyList() else listOf(dataDir.absolutePath)
        }

        return clear(dataDir, kept)
    }

    private fun clear(directory: File, keep: List<File>): List<String> {
        val failures = mutableListOf<String>()

        directory.listFiles()?.forEach { child ->
            val path = child.absoluteFile.normalize()
            val protected = keep.filter { it.toPath().startsWith(path.toPath()) }

            when {
                protected.isEmpty() -> if (!child.deleteRecursively()) failures.add(path.path)
                protected.any { it == path } -> Unit
                else -> failures.addAll(clear(child, protected))
            }
        }

        return failures
    }

    /**
     * Whether [pid] is alive and is the process that started at [startedAt] (epoch millis).
     *
     * An OS that cannot say when a process started gets the benefit of the doubt only when the
     * record has no start time either; otherwise a start time that is off by more than a few
     * seconds means the pid was reused.
     */
    fun isSameProcess(pid: Long, startedAt: Long?): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false

        if (!handle.isAlive) {
            return false
        }

        val start = handle.info().startInstant().orElse(null)?.toEpochMilli()

        if (startedAt == null || start == null) {
            return startedAt == null
        }

        return abs(start - startedAt) <= START_TOLERANCE_MILLIS
    }

    private const val RECORD_DIRECTORY = ".pano-node"
    private const val RECORD_FILE = "process.json"

    private const val START_TOLERANCE_MILLIS = 5_000L
    private const val STOP_GRACE_SECONDS = 30L
    private const val KILL_WAIT_SECONDS = 10L
}
