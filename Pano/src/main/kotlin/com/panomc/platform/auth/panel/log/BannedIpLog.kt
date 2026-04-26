package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class BannedIpLog(
    userId: Long,
    username: String,
    ip: String,
    reason: String,
    duration: Long,
    permanent: Boolean,
) : PanelActivityLog(
    userId = userId,
    type = "BANNED_IP",
    details = JsonObject()
        .put("username", username)
        .put("ip", ip)
        .put("reason", reason)
        .put("duration", duration)
        .put("permanent", permanent)
)
