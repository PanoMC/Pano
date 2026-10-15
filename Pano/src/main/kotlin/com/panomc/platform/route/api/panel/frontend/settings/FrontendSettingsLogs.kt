package com.panomc.platform.route.api.panel.frontend.settings

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class ChangedFrontendSettingsLog(
    userId: Long,
    username: String,
    frontendId: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("frontendId", frontendId)
)

class RefreshedFrontendDescriptorLog(
    userId: Long,
    username: String,
    frontendId: String?,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("frontendId", frontendId)
)
