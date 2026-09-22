package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] ran an action on an online player of a linked server.
 *
 * Kicking, opping or whitelisting through the panel is the same power as typing the command in the
 * console, so it is audited the same way.
 */
class ServerPlayerActionLog(
    userId: Long,
    username: String,
    serverId: Long,
    player: String,
    action: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("player", player)
        .put("action", action)
)
