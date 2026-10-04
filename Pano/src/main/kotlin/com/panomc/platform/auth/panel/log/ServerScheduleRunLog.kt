package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that a schedule ran, and how it went.
 *
 * No user id: nobody pressed anything, the clock did it. That is exactly why it is worth
 * recording — a server that stopped at 4 a.m. is otherwise indistinguishable from one that
 * crashed.
 */
class ServerScheduleRunLog(
    serverId: Long,
    name: String,
    ok: Boolean,
    error: String? = null
) : PanelActivityLog(
    details = JsonObject()
        .put("serverId", serverId)
        .put("name", name)
        .put("ok", ok)
        .put("error", error)
)
