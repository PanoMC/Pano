package com.panomc.platform.node

/**
 * How a node came to exist, for the panel to show and for support to ask about.
 *
 * It is not authorisation and nothing branches on it: [NodeKind] says where a node runs and
 * `approved` says whether it may. This says how it got here, which is the first question when a
 * node misbehaves — a hand-installed daemon on someone's laptop and a container Pano deployed to
 * Coolify fail in entirely different ways.
 */
enum class NodeBootstrap {
    /** Pano spawned it on its own machine. */
    LOCAL,

    /** Somebody ran the install script themselves and typed a pairing code. */
    MANUAL,

    /** Pano installed it over SSH from the panel. */
    SSH,

    /** Pano deployed it as a container through a Coolify instance. */
    COOLIFY;

    companion object {
        fun fromId(id: String?): NodeBootstrap? = entries.firstOrNull { it.name == id?.uppercase() }
    }
}
