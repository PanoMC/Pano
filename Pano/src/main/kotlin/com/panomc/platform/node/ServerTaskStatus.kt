package com.panomc.platform.node

/**
 * Lifecycle of a [ServerTaskKind] job.
 *
 * [PENDING] is Pano's own state: the row is written when the panel asks for the work and the push
 * goes out, but nothing has been heard back yet. A node only ever reports the other three, so a
 * task stuck on PENDING means the node never picked it up.
 */
enum class ServerTaskStatus {
    PENDING,
    RUNNING,

    /**
     * Waiting for the server to be restarted before the work can happen (SM-47, §2.4.17 C).
     *
     * Only a plugin-served restore reaches this: the plugin is running inside the very server
     * whose files would be overwritten, so it writes a marker and applies the backup on the next
     * `onLoad`, before a world is touched. Not terminal -- the `BACKUP_RESTORED` that follows the
     * next start is what ends the task -- and deliberately not RUNNING either, because nothing is
     * running and the panel has to say "applies on the next start" rather than show a spinner
     * that could sit there for weeks.
     */
    PENDING_RESTART,
    DONE,
    FAILED;

    /** Whether this is an end state, after which no further progress may be recorded. */
    val isTerminal get() = this == DONE || this == FAILED

    /** Whether the task is waiting on something outside Pano rather than making progress. */
    val isWaiting get() = this == PENDING || this == PENDING_RESTART

    companion object {
        fun fromId(id: String?): ServerTaskStatus? = entries.firstOrNull { it.name == id?.uppercase() }
    }
}
