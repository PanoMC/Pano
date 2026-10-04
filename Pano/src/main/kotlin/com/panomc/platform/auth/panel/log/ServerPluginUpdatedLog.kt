package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] updated a plugin on a managed server.
 *
 * Told apart from an install: the version that was running is the thing somebody needs weeks
 * later, when a server has started behaving differently and the question is what changed and who
 * changed it. An install has no "from", so it could not carry that even if it wanted to.
 */
class ServerPluginUpdatedLog(
    userId: Long,
    username: String,
    serverId: Long,
    serverName: String,
    pluginName: String,
    fromVersion: String?,
    toVersion: String?
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("serverName", serverName)
        .put("pluginName", pluginName)
        .put("fromVersion", fromVersion)
        .put("toVersion", toVersion)
)
