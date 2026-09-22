package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that Pano updated a node's daemon on its own (`managed-servers.node-auto-update`).
 *
 * No user id: nobody pressed anything, the node said hello with an older daemon than Pano serves
 * and Pano sent it the new one. Worth a line because the daemon restarts to apply it, and a node
 * that restarted by itself is otherwise a mystery. `agent` says whether it was a Pano Agent, which
 * the panel shows as its server rather than as a node.
 */
class AutoUpdatedNodeLog(
    nodeId: Long,
    name: String,
    fromVersion: String?,
    toVersion: String,
    sha256: String,
    agent: Boolean
) : PanelActivityLog(
    userId = null,
    details = JsonObject()
        .put("nodeId", nodeId)
        .put("name", name)
        .put("fromVersion", fromVersion)
        .put("toVersion", toVersion)
        .put("sha256", sha256)
        .put("agent", agent)
)
