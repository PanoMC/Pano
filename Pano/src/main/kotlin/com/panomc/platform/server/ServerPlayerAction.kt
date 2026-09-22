package com.panomc.platform.server

/**
 * Actions the panel can take on one online player of a linked server.
 *
 * [KICK] and [MESSAGE] are forwarded to the plugin as a `PLAYER_ACTION` push because it can do
 * them through its own API on every platform. The rest are turned into a console command by
 * [com.panomc.platform.server.players.ServerPlayerCommandComposer], since their syntax and even
 * their existence depend on the server software.
 *
 * [BAN] is neither: it bans the player's Pano account when they have one (website and every
 * server) and falls back to the server's own `ban` command when they do not.
 */
enum class ServerPlayerAction {
    KICK,
    MESSAGE,
    OP,
    DEOP,
    GAMEMODE,
    WHITELIST_ADD,
    WHITELIST_REMOVE,
    BAN;

    /** True when the plugin performs the action itself instead of Pano composing a command. */
    val isPluginAction get() = this == KICK || this == MESSAGE

    companion object {
        fun fromId(id: String): ServerPlayerAction? = entries.firstOrNull { it.name == id.uppercase() }
    }
}
