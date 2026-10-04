package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/** Records that [username] created a managed server on a node. */
class CreatedServerLog(
    userId: Long,
    username: String,
    serverId: Long,
    nodeId: Long,
    name: String,
    software: String,
    version: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("nodeId", nodeId)
        .put("name", name)
        .put("software", software)
        .put("version", version)
)
