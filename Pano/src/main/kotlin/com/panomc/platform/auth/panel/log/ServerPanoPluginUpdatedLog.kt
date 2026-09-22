package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] started an update of the Pano plugin itself on one server.
 *
 * Its own type rather than a [ServerPluginUpdatedLog]: the Pano plugin is the connection the panel
 * talks to the server through, so "who replaced it, from what, to what and by which route" is the
 * first question when a server stops showing up after a restart. [mode] is `node` or `plugin`, the
 * two routes an update can take; [fromVersion] is null for a server that never reported one.
 */
class ServerPanoPluginUpdatedLog(
    userId: Long,
    username: String,
    serverId: Long,
    serverName: String,
    fromVersion: String?,
    toVersion: String?,
    mode: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("serverName", serverName)
        .put("fromVersion", fromVersion)
        .put("toVersion", toVersion)
        .put("mode", mode)
)
