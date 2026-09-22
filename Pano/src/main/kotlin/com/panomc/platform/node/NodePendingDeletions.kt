package com.panomc.platform.node

/**
 * What a node's hello means for the servers Pano force-deleted on it (SM-64, §2.4.29 A).
 *
 * Pure, so the rule can be tested without a socket: a pending uuid the node still reports gets
 * `DELETE_SERVER` again, and one it no longer reports is done and forgotten. A uuid that has a
 * server row on this node again is forgotten without a delete — whatever brought the row back, the
 * directory now belongs to a server Pano knows, and deleting it would be deleting that server.
 */
object NodePendingDeletions {
    data class Plan(
        /** Still on the node: ask it to delete them (again). */
        val delete: List<String>,
        /** Gone from the node, or known to Pano again: drop the pending entry. */
        val settled: List<String>
    )

    fun plan(pending: Collection<String>, reported: Set<String>, known: Set<String>): Plan {
        val delete = mutableListOf<String>()
        val settled = mutableListOf<String>()

        pending.distinct().forEach { uuid ->
            if (uuid in reported && uuid !in known) {
                delete.add(uuid)
            } else {
                settled.add(uuid)
            }
        }

        return Plan(delete, settled)
    }
}
