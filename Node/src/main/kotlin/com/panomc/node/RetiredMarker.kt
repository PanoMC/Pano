package com.panomc.node

import io.vertx.core.json.JsonObject
import java.io.File

/**
 * The one file a removed node leaves in its data directory (SM-64, §2.4.29 B).
 *
 * Without it, deleting a node from Pano would leave a daemon that a service manager restarts, that
 * finds no token, and that either logs "not paired" every five seconds forever or — started with a
 * bootstrap token on its command line, like Pano's own local node — pairs all over again and turns
 * up in the panel as a brand new node. The marker is what makes the next start say "this node was
 * removed from Pano; nothing to do" and exit with [NodeVersion.RETIRED_EXIT_CODE] instead.
 *
 * Deleting the data directory (or just this file) is how an operator sets the host up again.
 */
object RetiredMarker {
    const val FILE_NAME = ".pano-node-retired"

    fun file(dataDir: File) = File(dataDir, FILE_NAME)

    fun isRetired(dataDir: File): Boolean = file(dataDir).isFile

    /** Writes the marker, saying when and from which Pano this node was removed. */
    fun write(dataDir: File, platformUrl: String?, name: String?, now: Long = System.currentTimeMillis()) {
        dataDir.mkdirs()

        file(dataDir).writeText(
            JsonObject()
                .put("retiredAt", now)
                .put("platformUrl", platformUrl)
                .put("name", name)
                .encodePrettily()
        )
    }

    /**
     * Empties [dataDir] of everything but the marker and whatever is in [keep].
     *
     * The last thing a retiring daemon does, after its socket is closed and its lock released, so
     * nothing it still runs can write a file back. [keep] is for the files this very process is
     * running from when they happen to live inside the data directory (its own jar, its JVM): those
     * cannot be removed from under a live process everywhere, and the operator's manual steps delete
     * the directory itself anyway. Returns the paths it could not remove.
     */
    fun clearAllBut(dataDir: File, keep: Collection<File> = emptyList()): List<String> =
        clearAllBut(
            dataDir,
            keep.map { it.absoluteFile.normalize() },
            file(dataDir).absoluteFile.normalize()
        )

    /**
     * A kept file deeper down keeps the directories above it; everything else in them goes.
     */
    private fun clearAllBut(directory: File, keep: List<File>, marker: File): List<String> {
        val failures = mutableListOf<String>()

        directory.listFiles()?.forEach { child ->
            val path = child.absoluteFile.normalize()

            if (path == marker) {
                return@forEach
            }

            val protected = keep.filter { it.toPath().startsWith(path.toPath()) }

            when {
                protected.isEmpty() -> if (!child.deleteRecursively()) failures.add(path.path)
                protected.any { it == path } -> Unit
                else -> failures.addAll(clearAllBut(child, protected, marker))
            }
        }

        return failures
    }
}
