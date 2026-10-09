package com.panomc.platform.route.api.panel.frontend.origins

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class UpdatedFrontendOriginsLog(
    userId: Long,
    username: String,
    count: Int,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("count", count)
)
