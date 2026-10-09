package com.panomc.platform.route.api.panel.frontend.mode

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class ChangedFrontendModeLog(
    userId: Long,
    username: String,
    mode: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("mode", mode)
)

class UploadedCustomAppLog(
    userId: Long,
    username: String,
    appId: String,
    version: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("appId", appId).put("version", version)
)

class DeletedCustomAppLog(
    userId: Long,
    username: String,
    appId: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("appId", appId)
)
