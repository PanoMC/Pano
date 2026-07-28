package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

class MaintenanceModeUnbannedIpLog(
    userId: Long,
    username: String,
    ips: List<String>,
) : PanelActivityLog(
    userId = userId,
    type = "MAINTENANCE_MODE_UNBANNED_IP",
    details = JsonObject()
        .put("username", username)
        .put("ips", JsonArray(ips))
        // details is handed to ICU as the translation values; an array argument is not safely
        // formattable, so the message uses these two scalars.
        .put("ipList", ips.joinToString(", "))
        .put("count", ips.size)
)
