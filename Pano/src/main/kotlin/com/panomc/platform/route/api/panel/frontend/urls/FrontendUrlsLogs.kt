package com.panomc.platform.route.api.panel.frontend.urls

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class ChangedFrontendUrlsLog(
    userId: Long,
    username: String,
    count: Int,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("count", count)
)
