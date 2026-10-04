package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] removed a node and how many servers were on it at the time.
 *
 * [removedFiles] says whether the node itself deleted what it held (SM-64): false for a forced
 * delete of a node that was offline, too old or failed to uninstall, whose files are still there.
 */
class DeletedNodeLog(
    userId: Long,
    username: String,
    nodeId: Long,
    name: String,
    serverCount: Long,
    removedFiles: Boolean = false
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("nodeId", nodeId)
        .put("name", name)
        .put("serverCount", serverCount)
        .put("removedFiles", removedFiles)
)
