package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Asks the plugin to act on one online player (`PLAYER_ACTION`).
 *
 * Only the actions the plugin can perform through its own API travel this way: KICK reuses the
 * existing kick helper and MESSAGE delivers [text] to that player. Everything else an admin can do
 * to a player (op, gamemode, whitelist) is composed into a console command on the Pano side
 * instead, because those differ per server platform.
 */
data class PlayerActionMessage(
    val action: String,
    val uuid: String,
    val username: String,
    val text: String?,
    val issuedBy: String
) : PlatformMessage
