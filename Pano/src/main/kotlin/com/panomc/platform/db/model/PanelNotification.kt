package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.notification.NotificationStatus
import io.vertx.core.json.JsonObject

data class PanelNotification(
    val id: Long = -1,
    val userId: Long,
    val type: String,
    val properties: JsonObject = JsonObject(),
    val date: Long = System.currentTimeMillis(),
    val status: NotificationStatus = NotificationStatus.NOT_READ
) : DBEntity()