package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] ran [command] on a linked server's console.
 *
 * Console access is effectively operator access to the Minecraft server, so every command is
 * auditable through the activity log, as required by the security rules in AGENT.md.
 */
class SentServerCommandLog(
    userId: Long,
    username: String,
    serverId: Long,
    command: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("command", command)
)
