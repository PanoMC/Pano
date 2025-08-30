package com.panomc.platform.auth.panel.log

import com.panomc.platform.InstallManager
import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class InstalledResourceLog(
    userId: Long,
    username: String,
    resourceId: String,
    to: String,
    type: InstallManager.Companion.ResourceType
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("resourceId", resourceId)
        .put("to", to)
        .put("type", type.name)
)