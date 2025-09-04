package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.notification.NotificationStatus
import com.panomc.platform.notification.NotificationType
import io.vertx.core.json.JsonObject

data class Notification(
    val id: Long = -1,
    val userId: Long,
    val type: NotificationType,
    val details: JsonObject = JsonObject(),
    val status: NotificationStatus = NotificationStatus.NOT_READ,
    var updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
) : DBEntity()