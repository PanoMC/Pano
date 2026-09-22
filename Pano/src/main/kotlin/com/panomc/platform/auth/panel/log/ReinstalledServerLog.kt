package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.server.software.SoftwareChangeKeep
import io.vertx.core.json.JsonObject

/**
 * Records that [username] reinstalled a managed server, possibly on another software (SM-66).
 *
 * [oldSoftware]/[oldVersion] are what it ran before (null for an imported row that never had one)
 * and [keep] what was asked to be carried over; both are absent from entries written before SM-66.
 */
class ReinstalledServerLog(
    userId: Long,
    username: String,
    serverId: Long,
    software: String,
    version: String,
    oldSoftware: String? = null,
    oldVersion: String? = null,
    keep: SoftwareChangeKeep? = null
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("software", software)
        .put("version", version)
        .put("oldSoftware", oldSoftware)
        .put("oldVersion", oldVersion)
        .put("keep", keep?.toJson())
)
