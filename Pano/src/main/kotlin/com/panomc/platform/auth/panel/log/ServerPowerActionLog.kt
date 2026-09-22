package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] stopped or restarted a linked server from the panel.
 *
 * Taking a server down affects every player on it, so it is audited like the other destructive
 * panel actions.
 */
class ServerPowerActionLog(
    userId: Long,
    username: String,
    serverId: Long,
    action: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("action", action)
)
