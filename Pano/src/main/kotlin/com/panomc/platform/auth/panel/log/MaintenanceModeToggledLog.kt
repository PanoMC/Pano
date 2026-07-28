package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class MaintenanceModeToggledLog(
    userId: Long,
    username: String,
    enabled: Boolean,
) : PanelActivityLog(
    userId = userId,
    type = "MAINTENANCE_MODE_TOGGLED",
    details = JsonObject()
        .put("username", username)
        .put("enabled", enabled)
)
