package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class ClosedTicketsLog(
    userId: Long,
    username: String,
    ticketIds: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("ticketIds", ticketIds)
)