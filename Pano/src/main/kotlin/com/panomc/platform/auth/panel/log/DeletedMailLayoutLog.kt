package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class DeletedMailLayoutLog(
    userId: Long,
    username: String,
    layoutId: String,
    layoutName: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("layoutId", layoutId)
        .put("layoutName", layoutName)
)
