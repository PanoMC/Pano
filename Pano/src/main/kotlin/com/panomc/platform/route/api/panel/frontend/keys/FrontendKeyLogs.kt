package com.panomc.platform.route.api.panel.frontend.keys

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class CreatedFrontendKeyLog(
    userId: Long,
    username: String,
    keyName: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("name", keyName)
)

class DeletedFrontendKeyLog(
    userId: Long,
    username: String,
    keyName: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("name", keyName)
)
