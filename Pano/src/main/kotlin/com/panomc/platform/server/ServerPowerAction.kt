package com.panomc.platform.server

/**
 * Power actions the panel can ask for.
 *
 * Which ones are available depends on who owns the process, not on the enum. A linked server runs
 * outside Pano, so only the two a plugin can perform on itself exist there: nothing on this side
 * owns a process it could start, and "kill" has no meaning when the request has to travel through
 * the very process being killed. A managed server is owned by a node, which can do all four.
 */
enum class ServerPowerAction {
    START,
    STOP,
    RESTART,
    KILL;

    /** Whether the Pano plugin inside a linked server can carry this out on its own. */
    val isSupportedByPlugin get() = this == STOP || this == RESTART

    companion object {
        fun fromId(id: String?): ServerPowerAction? = entries.firstOrNull { it.name == id?.uppercase() }
    }
}
