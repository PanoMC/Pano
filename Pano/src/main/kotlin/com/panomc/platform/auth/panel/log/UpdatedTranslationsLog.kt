package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class UpdatedTranslationsLog(
    userId: Long,
    username: String,
    localeId: Long,
    localeName: String,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("localeId", localeId)
        .put("localeName", localeName)
)