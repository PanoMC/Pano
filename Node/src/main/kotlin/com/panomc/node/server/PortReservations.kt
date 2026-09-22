package com.panomc.node.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Ports this node has promised to a server that does not exist yet.
 *
 * [ServerRegistry.takenPorts] can only speak for servers that are already registered, and an
 * install is registered at the *end*: it downloads a jar first, which takes minutes. Everything in
 * between is a hole — two installs that arrive seconds apart both look at the same registry, see
 * the same free number and take it, and the second server to finish cannot bind.
 *
 * That is not hypothetical either. Pano allocated 25567 for a new server while an import
 * running at the same time was handed 25567 by this node, and both wrote it into their
 * `server.properties`.
 *
 * So the port is claimed the moment the message arrives, before any work starts, and released once
 * the registry has it or the task has failed. Keyed by server uuid rather than kept as a bare set,
 * so a retry of the same install replaces its own claim instead of leaking one, and so a release
 * can never take another server's port with it.
 *
 * In memory only, like everything else about a task in flight: a node that restarts mid-install
 * has no half-installed server to protect, because nothing was registered.
 */
class PortReservations {
    private val reserved = ConcurrentHashMap<String, Int>()

    /** Claims [port] for [uuid]. A port of zero or less is not a claim and is ignored. */
    fun reserve(uuid: String, port: Int?) {
        if (port == null || port <= 0) {
            release(uuid)

            return
        }

        reserved[uuid] = port
    }

    /** Drops [uuid]'s claim, whether it succeeded or failed. */
    fun release(uuid: String) {
        reserved.remove(uuid)
    }

    /** Every port claimed right now except [exceptUuid]'s own, which it is free to keep. */
    fun ports(exceptUuid: String? = null): Set<Int> = reserved
        .filterKeys { it != exceptUuid }
        .values
        .toSet()

    /** How many claims are outstanding. Exposed for tests and leak checks. */
    fun size() = reserved.size
}
