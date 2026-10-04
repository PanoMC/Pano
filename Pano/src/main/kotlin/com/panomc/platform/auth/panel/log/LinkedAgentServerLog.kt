package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that a Pano Agent connected for the first time and Pano linked its server.
 *
 * Credited to whoever fetched the agent code when Pano still knows who that was ([userId] and
 * [username]); otherwise nobody -- the agent pairing itself is what created the server.
 */
class LinkedAgentServerLog(
    userId: Long?,
    username: String?,
    serverId: Long,
    nodeId: Long,
    name: String,
    directory: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("nodeId", nodeId)
        .put("name", name)
        .put("directory", directory)
)
