package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Automatic brute-force ban issued by maintenance mode. There is no acting user, so [userId] is
 * null — [BannedIpLog] cannot be reused because it models an admin banning someone and requires a
 * non-null user.
 */
class MaintenanceModeBannedIpLog(
    ip: String,
    socketPeer: String,
    attempts: Int,
    usernameTried: String?,
    userAgent: String?,
) : PanelActivityLog(
    userId = null,
    type = "MAINTENANCE_MODE_BANNED_IP",
    details = JsonObject()
        .put("ip", ip)
        .put("socketPeer", socketPeer)
        .put("attempts", attempts)
        .put("usernameTried", usernameTried)
        .put("userAgent", userAgent)
)
