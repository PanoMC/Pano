package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class UnbannedIpLog(
    userId: Long,
    username: String,
    ip: String,
) : PanelActivityLog(
    userId = userId,
    type = "UNBANNED_IP",
    details = JsonObject()
        .put("username", username)
        .put("ip", ip)
)
