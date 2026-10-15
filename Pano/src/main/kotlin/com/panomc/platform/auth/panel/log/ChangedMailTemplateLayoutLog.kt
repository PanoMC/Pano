package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class ChangedMailTemplateLayoutLog(
    userId: Long,
    username: String,
    owner: String,
    mailKey: String,
    layout: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("owner", owner)
        .put("mailKey", mailKey)
        .put("layout", layout)
)
