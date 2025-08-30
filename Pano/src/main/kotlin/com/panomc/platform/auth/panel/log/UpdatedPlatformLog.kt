package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class UpdatedPlatformLog(
    userId: Long,
    username: String,
    from: String,
    to: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("from", from)
        .put("to", to)
)