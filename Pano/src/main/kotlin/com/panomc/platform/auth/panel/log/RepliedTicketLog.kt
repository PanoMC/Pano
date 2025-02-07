package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class RepliedTicketLog(
    userId: Long,
    username: String,
    ticketId: Long,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("ticketId", ticketId)
)