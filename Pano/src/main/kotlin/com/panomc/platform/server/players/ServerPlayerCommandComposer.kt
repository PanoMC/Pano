package com.panomc.platform.server.players

import com.panomc.platform.error.BadRequest
import com.panomc.platform.server.ServerPlayerAction
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject

/**
 * Turns a player action into the console command that performs it on a given server platform.
 *
 * The command is built here, on the server, from the username Pano already has on record for that
 * player rather than from anything the caller typed, and the username is re-validated before it is
 * put into a command string. A command is a single line dispatched to the console sender, so a
 * username containing a space or a newline would silently turn one command into another.
 *
 * Proxies are rejected for the actions they have no concept of: Velocity and BungeeCord do not run
 * a world, so op, deop and gamemode do not exist there.
 */
object ServerPlayerCommandComposer {
    /** Game modes accepted for [ServerPlayerAction.GAMEMODE]. */
    val GAME_MODES = setOf("survival", "creative", "adventure", "spectator")

    // Deliberately narrow: Minecraft usernames are [A-Za-z0-9_], and Bedrock names arriving
    // through Geyser add a dot. Anything else never reaches a command line.
    private val SAFE_USERNAME = Regex("^[A-Za-z0-9_.]{1,32}$")

    /**
     * Composes the command for [action] on a [serverType] server.
     *
     * Throws [BadRequest] when the action is not a command action at all, when the platform cannot
     * perform it, when [username] is not a name that is safe to put in a command, or when
     * [gamemode] is missing or unknown for a gamemode change.
     */
    fun compose(
        action: ServerPlayerAction,
        serverType: ServerType,
        username: String,
        gamemode: String? = null
    ): String {
        if (action.isPluginAction || action == ServerPlayerAction.BAN) {
            throw BadRequest()
        }

        if (!SAFE_USERNAME.matches(username)) {
            throw BadRequest()
        }

        if (serverType.isProxy && action != ServerPlayerAction.WHITELIST_ADD &&
            action != ServerPlayerAction.WHITELIST_REMOVE
        ) {
            throw BadRequest()
        }

        return when (action) {
            ServerPlayerAction.OP -> "op $username"
            ServerPlayerAction.DEOP -> "deop $username"
            ServerPlayerAction.GAMEMODE -> {
                val mode = gamemode?.lowercase()

                if (mode == null || mode !in GAME_MODES) {
                    throw BadRequest()
                }

                "gamemode $mode $username"
            }

            ServerPlayerAction.WHITELIST_ADD -> "whitelist add $username"
            ServerPlayerAction.WHITELIST_REMOVE -> "whitelist remove $username"
            else -> throw BadRequest()
        }
    }

    /**
     * [compose] widened to the two actions the plugin would normally perform itself (§2.4.17 B).
     *
     * A server with no Pano plugin in it still has a console, and a console can kick and can
     * message -- so the node path composes those as commands rather than losing the buttons. It is
     * the same line the plugin path would have produced for everything else, which is the point:
     * one set of strings, two ways of delivering them.
     *
     * [text] is a kick reason or the message body and is the only part that did not come from
     * Pano's own records, so it is refused outright if it carries anything that could end the line
     * and start a second command. A message goes out as `tellraw` rather than `msg` because its
     * payload is JSON, and JSON encoding is a boundary the text cannot argue with.
     */
    fun composeForConsole(
        action: ServerPlayerAction,
        serverType: ServerType,
        username: String,
        text: String? = null,
        gamemode: String? = null
    ): String {
        if (!action.isPluginAction) {
            return compose(action, serverType, username, gamemode)
        }

        if (!SAFE_USERNAME.matches(username)) {
            throw BadRequest()
        }

        val body = cleanText(text)

        return when (action) {
            ServerPlayerAction.KICK ->
                if (body == null) "kick $username" else "kick $username $body"

            // A proxy has no tellraw, and no console command that privately messages one player
            // either, so this is a button the node path cannot offer there.
            ServerPlayerAction.MESSAGE -> {
                if (serverType.isProxy || body == null) {
                    throw BadRequest()
                }

                "tellraw $username " + JsonObject().put("text", body).encode()
            }

            else -> throw BadRequest()
        }
    }

    /**
     * The server's own `ban <player> [reason]`, for a player a Pano ban cannot reach (no account)
     * or a server that does not enforce Pano bans. A proxy has no such command.
     */
    fun composeBan(serverType: ServerType, username: String, reason: String? = null): String {
        if (serverType.isProxy || !SAFE_USERNAME.matches(username)) {
            throw BadRequest()
        }

        val body = cleanText(reason)

        return if (body == null) "ban $username" else "ban $username $body"
    }

    /** Free text that is safe to put on a console line, or null when there was none. */
    private fun cleanText(text: String?): String? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (trimmed.any { it == '\n' || it == '\r' || it.code < 0x20 }) {
            throw BadRequest()
        }

        return trimmed
    }
}
