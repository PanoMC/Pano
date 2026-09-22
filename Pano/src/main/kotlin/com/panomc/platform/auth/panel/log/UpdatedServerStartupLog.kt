package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/** Records that [username] changed a managed server's startup settings. */
class UpdatedServerStartupLog(
    userId: Long,
    username: String,
    serverId: Long
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
)
