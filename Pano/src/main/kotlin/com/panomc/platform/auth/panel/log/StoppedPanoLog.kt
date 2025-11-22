package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class StoppedPanoLog(
    userId: Long,
    username: String
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username)
)
