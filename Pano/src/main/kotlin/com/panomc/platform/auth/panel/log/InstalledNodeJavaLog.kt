package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/** Records that [username] asked a node to download (or update) Java [major] (SM-63). */
class InstalledNodeJavaLog(
    userId: Long,
    username: String,
    nodeId: Long,
    name: String,
    major: Int
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("nodeId", nodeId)
        .put("name", name)
        .put("major", major)
)
