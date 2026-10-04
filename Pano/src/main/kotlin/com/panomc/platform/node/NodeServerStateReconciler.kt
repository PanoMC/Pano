package com.panomc.platform.node

import com.panomc.platform.server.ServerProcessState

/**
 * Works out which managed servers changed state while Pano was not listening.
 *
 * A node's hello is the only moment Pano learns what actually happened during a disconnect: Pano
 * may have been restarted, the node may have been restarted, and the `processState` column is
 * whatever was true before the gap. This computes the corrections rather than blindly overwriting,
 * so a hello that repeats what Pano already knows produces no writes and no panel churn.
 *
 * The asymmetry is deliberate. A server the node reports is believed outright — the node owns the
 * process. A server the node does *not* mention is only knocked down when Pano currently thinks it
 * is alive, because an unmentioned server is one the node is not running; INSTALLING, CRASHED and
 * STOPPED are left exactly as they are, since those are states a missing entry cannot disprove and
 * an install in flight is tracked by its task, not by this.
 *
 * Since SM-51 a reported entry also carries whether the node *adopted* that process rather than
 * starting it. That never changes the state — an adopted server is RUNNING like any other, and
 * this is precisely the reconciliation that used to flip it to STOPPED — but it does have to be
 * recorded, because it is what the panel's header hint and the console's input box are drawn from.
 */
object NodeServerStateReconciler {
    /** A server as Pano currently has it on record. */
    data class Known(
        val serverId: Long,
        val uuid: String,
        val processState: ServerProcessState?,
        val adopted: Boolean = false,
        val stdinAvailable: Boolean = true,
        val lastExitCode: Int? = null
    )

    /** One entry of the node's hello, already narrowed to what reconciliation cares about. */
    data class Reported(
        val state: String?,
        val adopted: Boolean = false,
        val stdinAvailable: Boolean = true,
        /** The exit code the node knows for a server that is down (SM-62), or null. */
        val exitCode: Int? = null
    )

    /**
     * One correction to apply: [serverId] moves to [state] with these two flags, and [exitCode] as
     * its last exit code (only ever set for a server that is down).
     */
    data class Change(
        val serverId: Long,
        val state: ServerProcessState,
        val adopted: Boolean = false,
        val stdinAvailable: Boolean = true,
        val exitCode: Int? = null
    )

    /**
     * Compares what Pano has ([known]) with what the node just reported ([reported], by uuid) and
     * returns only the rows that need updating.
     *
     * Reported states this Pano version does not recognise are ignored, and reported uuids that
     * belong to no known server are ignored too: the caller has already narrowed [known] to the
     * servers that belong to this node, and anything outside that set is not the node's to name.
     */
    fun reconcile(known: List<Known>, reported: Map<String, Reported>): List<Change> {
        val changes = mutableListOf<Change>()

        known.forEach { server ->
            val entry = reported[server.uuid]
            val reportedState = ServerProcessState.fromId(entry?.state)

            if (entry != null && reportedState != null) {
                // Only a server that is down has an exit code; one the node does not know leaves
                // whatever Pano has alone rather than wiping it.
                val exitCode = entry.exitCode.takeIf { !reportedState.isAlive && reportedState != ServerProcessState.INSTALLING }
                val newExitCode = exitCode != null && exitCode != server.lastExitCode

                val moved = reportedState != server.processState ||
                        entry.adopted != server.adopted ||
                        entry.stdinAvailable != server.stdinAvailable ||
                        newExitCode

                if (moved) {
                    changes.add(
                        Change(
                            server.serverId,
                            reportedState,
                            entry.adopted,
                            entry.stdinAvailable,
                            exitCode ?: server.lastExitCode.takeIf { reportedState == server.processState }
                        )
                    )
                }

                return@forEach
            }

            // Not reported (or reported with a state this version cannot read): only a state that
            // claims a live process is contradicted by the node's silence. The flags go with it —
            // there is no process left to have been adopted or to have lost its stdin.
            if (server.processState != null && server.processState.isAlive) {
                changes.add(Change(server.serverId, ServerProcessState.STOPPED))
            }
        }

        return changes
    }
}
