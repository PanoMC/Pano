package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class DeletedThemeLog(
    userId: Long,
    username: String,
    themeId: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("themeId", themeId)
)