package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class DeletedTicketCategoryLog(
    userId: Long,
    username: String,
    title: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("title", title)
)