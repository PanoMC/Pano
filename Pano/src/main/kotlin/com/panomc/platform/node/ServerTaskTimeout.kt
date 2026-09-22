package com.panomc.platform.node

/**
 * When a task that nobody is reporting on any more should be given up on.
 *
 * A task is a promise between Pano and a node, and the node is the only party that can keep it.
 * When the node never picks the work up, or picks it up and then dies mid-download, nothing else
 * in the system ever closes the row: the panel shows a spinner that will still be spinning
 * tomorrow, a server stays INSTALLING and can never be started, and the only way out is the
 * database. That happened for real — a node running an older protocol silently ignored a
 * `PLUGIN_INSTALL` push and its task sat on PENDING indefinitely.
 *
 * Two deadlines rather than one, because the two silences mean different things. A PENDING task
 * has not been acknowledged at all: the push either reached a node that does not understand it or
 * did not reach one, and neither improves with waiting, so two minutes is already generous. A
 * RUNNING task is work in progress with a gap in its reporting, and the gap is allowed to be long
 * — a 208 MB backup or a slow mirror can go minutes between frames — so it gets ten.
 *
 * Both are measured from the last sign of life (`updatedAt`), not from creation: a task that keeps
 * reporting is never timed out however long it legitimately runs.
 */
object ServerTaskTimeout {
    /** How long a task may sit unacknowledged before Pano concludes no node took it. */
    const val PENDING_TIMEOUT_MS = 2 * 60 * 1000L

    /** How long a running task may go without a frame before it counts as abandoned. */
    const val RUNNING_TIMEOUT_MS = 10 * 60 * 1000L

    /** Written into the task's `error` so the panel can say what happened rather than "failed". */
    const val TIMEOUT_ERROR = "TIMEOUT"

    /** The deadline for [status], or null when the status is not one that can time out. */
    fun timeoutMsFor(status: ServerTaskStatus): Long? = when (status) {
        ServerTaskStatus.PENDING -> PENDING_TIMEOUT_MS
        ServerTaskStatus.RUNNING -> RUNNING_TIMEOUT_MS
        // A restore armed for the next start has no deadline at all: nobody has promised when
        // the server will be restarted, and failing it after ten minutes would mean the panel
        // says the restore failed and then it happens anyway (§2.4.17 C).
        ServerTaskStatus.PENDING_RESTART -> null
        ServerTaskStatus.DONE, ServerTaskStatus.FAILED -> null
    }

    /**
     * Whether a task last updated at [updatedAt] has been silent long enough to fail.
     *
     * A clock that moved backwards (an NTP correction, a machine resumed from sleep) makes the
     * elapsed time negative; that is treated as "no time has passed" rather than as a timeout, so
     * a stepped clock never fails a task that is working perfectly well.
     */
    fun hasTimedOut(status: ServerTaskStatus, updatedAt: Long, now: Long): Boolean {
        val timeout = timeoutMsFor(status) ?: return false

        val elapsed = now - updatedAt

        return elapsed >= timeout
    }
}
