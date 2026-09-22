package com.panomc.platform.server

/**
 * Lifecycle of a managed server's process, as reported by the node that owns it.
 *
 * This is deliberately separate from [ServerStatus], which says whether the Pano plugin inside the
 * server is connected. A server can be RUNNING with no plugin connection (still booting, or the
 * plugin is not installed) and it can be CRASHED while Pano still remembers its last roster, so
 * the two never collapse into one column.
 *
 * `null` on a server row means "no process Pano owns", i.e. a linked server.
 */
enum class ServerProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    CRASHED,
    INSTALLING;

    /** Whether a process exists right now, which is what the panel gates STOP/KILL on. */
    val isAlive get() = this == STARTING || this == RUNNING || this == STOPPING

    companion object {
        /** Resolves a node-reported state, or `null` when this Pano version does not know it. */
        fun fromId(id: String?): ServerProcessState? = entries.firstOrNull { it.name == id?.uppercase() }
    }
}
