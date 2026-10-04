package com.panomc.platform.node

/**
 * How a node runs the servers it owns.
 *
 * [PROCESS] launches a JVM child process per server. [DOCKER] gives each server its own
 * container; which one is in use is announced by the node in `NODE_HELLO` and stored on its row,
 * and the difference never reaches the protocol — the same messages drive both.
 */
enum class NodeRuntime {
    PROCESS,
    DOCKER;

    companion object {
        /** Resolves a node-reported runtime, falling back to [PROCESS] for anything unknown. */
        fun fromId(id: String?): NodeRuntime = fromIdOrNull(id) ?: PROCESS

        /**
         * The runtime a node named, or null when it named none Pano knows.
         *
         * Null rather than [PROCESS] because the two are different answers where a stored value
         * already exists: a node too old to report a runtime, or one reporting a runtime from a
         * newer Pano, has said nothing about it — and overwriting `DOCKER` with a guess would be
         * worse than leaving the row alone.
         */
        fun fromIdOrNull(id: String?): NodeRuntime? {
            val name = id?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: return null

            return entries.firstOrNull { it.name == name }
        }
    }
}
