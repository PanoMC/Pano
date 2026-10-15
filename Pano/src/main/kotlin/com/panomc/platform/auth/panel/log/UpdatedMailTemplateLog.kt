package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class UpdatedMailTemplateLog(
    userId: Long,
    username: String,
    owner: String,
    mailKey: String,
    variant: String,
    localeCode: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("owner", owner)
        .put("mailKey", mailKey)
        .put("variant", variant)
        .put("localeCode", localeCode)
)
