package com.panomc.platform.db.model

import io.vertx.core.json.JsonObject

open class PluginActivityLog(
    userId: Long,
    pluginId: String,
    details: JsonObject = JsonObject()
) : PanelActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = details
)
