package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that a managed server's process died without being asked to.
 *
 * The only activity log with no user behind it: nobody did this, the node observed it. It is still
 * an activity log rather than only a notification because notifications get read and dismissed,
 * while "how often does this server crash" is a question asked weeks later.
 */
class ServerCrashedLog(
    serverId: Long,
    name: String,
    exitCode: Int?
) : PanelActivityLog(
    userId = null,
    details = JsonObject()
        .put("serverId", serverId)
        .put("name", name)
        .put("exitCode", exitCode)
)
