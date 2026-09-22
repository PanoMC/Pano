package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/** Records that [username] renamed a node. */
class RenamedNodeLog(
    userId: Long,
    username: String,
    nodeId: Long,
    name: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("nodeId", nodeId)
        .put("name", name)
)
