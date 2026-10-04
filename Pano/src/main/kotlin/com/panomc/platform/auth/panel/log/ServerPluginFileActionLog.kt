package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] installed or removed a plugin jar on a managed server.
 *
 * Separate from [ServerPluginToggledLog]: enabling a plugin is reversible from the same screen,
 * while putting third-party code into a server directory (or taking it out from under a running
 * game) is the kind of change somebody later needs to be able to trace to a person.
 */
class ServerPluginFileActionLog(
    userId: Long,
    username: String,
    serverId: Long,
    action: String,
    filename: String,
    source: String? = null,
    projectId: String? = null
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("action", action)
        .put("filename", filename)
        .put("source", source)
        .put("projectId", projectId)
) {
    companion object {
        const val ACTION_INSTALL = "INSTALL"
        const val ACTION_REMOVE = "REMOVE"
    }
}
