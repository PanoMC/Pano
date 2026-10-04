package com.panomc.node.files

import com.panomc.node.agent.AgentLayout

/**
 * The files inside a managed server that the file manager may never touch.
 *
 * Two of them hold credentials — `plugins/Pano/config.conf` (and its Velocity and Fabric
 * equivalents) carries the token and AES key that let a process act as this server against Pano —
 * and `server.json` is what the daemon reads its own launch arguments from, so editing it through
 * the file manager would be a way to change the java binary a node executes. `hs_err_*` crash
 * dumps are excluded because they are JVM memory dumps, not because they are dangerous.
 *
 * Two levels, on purpose. A denied path is invisible and untouchable. A directory that *contains*
 * one stays readable and listable — `plugins/` has to be, it is the point of the file manager —
 * but cannot be deleted or renamed out from under the file it holds.
 */
object ServerFileDenylist {
    /** Paths, relative to the server directory, that are never listed, read, written or removed. */
    val DENIED_PATHS = listOf(
        "server.json",
        // The daemon's own folder, which holds the ownership record a restart adopts a running
        // process from: a writable process.json would let the file manager point the node at any
        // pid on the host and then offer a Stop button for it.
        ".pano-node",
        // A Pano Agent's data folder, next to the server it runs (SM-74): its config.conf is the
        // token that lets a process act as this agent, and its backups/ and java/ are the node's.
        AgentLayout.DATA_DIRECTORY,
        // Every data folder the Pano plugin uses, because which one applies depends on the
        // platform and the node does not have to know which one this server is.
        "plugins/Pano/config.conf",
        "plugins/pano/config.conf",
        "config/pano/config.conf"
    )

    private const val CRASH_DUMP_PREFIX = "hs_err_"

    /** [path] with separators normalised and any leading or trailing slash removed. */
    fun normalise(path: String?): String = (path ?: "")
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")

    /**
     * Whether [path] is one of the files that does not exist as far as Pano is concerned.
     *
     * Case-insensitive: the same file is `plugins/Pano` on Linux and `plugins/pano` on a
     * case-folding filesystem, and a rule that only holds on one of them is not a rule.
     */
    fun isDenied(path: String?): Boolean {
        val normalised = normalise(path)

        if (normalised.isEmpty()) {
            return false
        }

        val lower = normalised.lowercase()

        if (lower.substringAfterLast('/').startsWith(CRASH_DUMP_PREFIX)) {
            return true
        }

        return DENIED_PATHS.any { denied ->
            val deniedLower = denied.lowercase()

            lower == deniedLower || lower.startsWith("$deniedLower/")
        }
    }

    /**
     * Whether [path] is a directory a denied file lives in.
     *
     * Used by the destructive operations only: deleting `plugins` would take this server's
     * credentials with it, and renaming it would leave the plugin looking at a folder that is no
     * longer there.
     */
    fun containsDenied(path: String?): Boolean {
        val normalised = normalise(path)

        if (normalised.isEmpty()) {
            // The server directory itself contains all of them, and nothing is allowed to remove
            // or rename it through the file manager anyway.
            return true
        }

        val lower = normalised.lowercase()

        return DENIED_PATHS.any { denied -> denied.lowercase().startsWith("$lower/") }
    }

    /** Whether a destructive operation may touch [path]. */
    fun isMutable(path: String?): Boolean = !isDenied(path) && !containsDenied(path)
}
