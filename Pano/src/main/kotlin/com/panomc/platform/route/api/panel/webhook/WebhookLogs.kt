package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

class CreatedWebhookLog(userId: Long, username: String, name: String) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("name", name)
)

class UpdatedWebhookLog(userId: Long, username: String, name: String) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("name", name)
)

class DeletedWebhookLog(userId: Long, username: String, name: String) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("name", name)
)

class RedeliveredWebhookLog(userId: Long, username: String, name: String) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("name", name)
)
