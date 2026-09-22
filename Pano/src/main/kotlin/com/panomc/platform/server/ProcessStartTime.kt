package com.panomc.platform.server

/**
 * When a managed server's process started, as the row keeps it — what the Overview's Uptime is
 * counted from for a server with no Pano plugin in it (`server.startTime` is the plugin's).
 *
 * Set when the node reports the process RUNNING, from the start time the node knows (the spawn, or
 * the recorded start of an adopted process); kept while it is RUNNING or STOPPING, because the same
 * process is still there; gone in every other state, because there is then no process whose uptime
 * it could be.
 */
object ProcessStartTime {
    /** Whether a recorded start time still describes a live process in [state]. */
    fun keeps(state: ServerProcessState?): Boolean =
        state == ServerProcessState.RUNNING || state == ServerProcessState.STOPPING

    /**
     * The start time to record for a process the node reports RUNNING: its own [startedAt], or —
     * from a node too old to send one — the moment it became RUNNING, or failing that now.
     */
    fun whenRunning(startedAt: Long?, since: Long?, now: Long): Long =
        startedAt?.takeIf { it > 0 } ?: since?.takeIf { it > 0 } ?: now

    /** What the row should hold after the node reported [state], given it holds [current]. */
    fun next(current: Long?, state: ServerProcessState, startedAt: Long?, since: Long?, now: Long): Long? = when {
        state == ServerProcessState.RUNNING -> whenRunning(startedAt, since, now)
        keeps(state) -> current
        else -> null
    }
}
