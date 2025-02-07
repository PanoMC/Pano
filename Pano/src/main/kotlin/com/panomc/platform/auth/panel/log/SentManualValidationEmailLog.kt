package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class SentManualValidationEmailLog(
    userId: Long,
    username: String,
    email: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("email", email)
)