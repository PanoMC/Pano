package com.panomc.platform.node

import java.util.concurrent.ConcurrentHashMap

/**
 * At most one automatic update attempt per node, per served jar, per [windowMillis].
 *
 * The loop this breaks: Pano sends `SELF_UPDATE`, the node downloads, verifies, stages, exits with
 * 75 and comes back with a hello -- and if anything in that chain failed (a checksum mismatch, a
 * read-only install directory, Windows holding the jar), the hello still says "older than what Pano
 * serves" and the update would be sent again, and again, restarting the daemon every few seconds.
 * So an attempt is remembered by node and by the sha256 it offered, and the same offer is not
 * repeated for thirty minutes. A *different* jar (a new release, or a development rebuild) is a new
 * offer and goes out at once; the manual update button is never throttled.
 *
 * In memory on purpose: a Pano restart is a fine moment to try again, and a table for this would
 * outlive every reason to have it.
 */
class NodeAutoUpdateThrottle(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    private data class Attempt(val sha256: String, val at: Long)

    private val attempts = ConcurrentHashMap<Long, Attempt>()

    /**
     * Whether an automatic attempt to hand [nodeId] the jar [sha256] may go out at [now], and if so
     * records it. Answering and recording are one step so two hellos racing each other cannot both
     * be told yes.
     */
    fun tryAcquire(nodeId: Long, sha256: String, now: Long = System.currentTimeMillis()): Boolean {
        var allowed = false

        attempts.compute(nodeId) { _, previous ->
            val repeat = previous != null &&
                previous.sha256.equals(sha256, ignoreCase = true) &&
                now - previous.at < windowMillis

            if (repeat) {
                previous
            } else {
                allowed = true

                Attempt(sha256, now)
            }
        }

        return allowed
    }

    /** Drops what is remembered about [nodeId], e.g. when the node is deleted. */
    fun forget(nodeId: Long) {
        attempts.remove(nodeId)
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 30 * 60 * 1000L
    }
}
