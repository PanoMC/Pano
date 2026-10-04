package com.panomc.platform.node

/**
 * Where a node runs relative to Pano.
 *
 * [LOCAL] is the node Pano spawns on its own machine and supervises like it supervises the UI
 * processes; it pairs with a one-time bootstrap token and is approved on sight. [REMOTE] is any
 * other host, which pairs with the rotating six-digit code and waits for an admin to accept it.
 */
enum class NodeKind {
    LOCAL,
    REMOTE;

    companion object {
        fun fromId(id: String?): NodeKind? = entries.firstOrNull { it.name == id?.uppercase() }
    }
}
