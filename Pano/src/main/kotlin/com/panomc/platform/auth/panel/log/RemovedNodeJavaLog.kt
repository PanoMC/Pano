package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] asked a node to remove its managed Java [major] (SM-63). [version] is the
 * one that was picked, or null for "the managed runtime of that major".
 */
class RemovedNodeJavaLog(
    userId: Long,
    username: String,
    nodeId: Long,
    name: String,
    major: Int,
    version: String?
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("nodeId", nodeId)
        .put("name", name)
        .put("major", major)
        .put("version", version)
)
