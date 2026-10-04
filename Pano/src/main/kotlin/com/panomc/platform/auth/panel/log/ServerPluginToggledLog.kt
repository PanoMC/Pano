package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/** Records that [username] enabled or disabled a plugin on a linked server. */
class ServerPluginToggledLog(
    userId: Long,
    username: String,
    serverId: Long,
    plugin: String,
    enabled: Boolean
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("plugin", plugin)
        .put("enabled", enabled)
)
