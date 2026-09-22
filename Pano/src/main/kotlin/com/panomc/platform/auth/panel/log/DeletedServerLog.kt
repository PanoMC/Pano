package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] removed a server from Pano.
 *
 * [force] says whether the files on the node were left behind, which is exactly the detail someone
 * needs weeks later when a directory turns up that nothing claims.
 */
class DeletedServerLog(
    userId: Long,
    username: String,
    serverId: Long,
    name: String,
    force: Boolean
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("name", name)
        .put("force", force)
)
