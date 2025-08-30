package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class EnabledPluginLog(
    userId: Long,
    username: String,
    pluginId: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("pluginId", pluginId)
)