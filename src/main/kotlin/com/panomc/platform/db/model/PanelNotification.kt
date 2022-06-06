package com.panomc.platform.db.model

import com.panomc.platform.util.NotificationStatus

data class PanelNotification(
    val id: Int,
    val userId: Int,
    val typeId: String,
    val date: Long,
    val status: NotificationStatus
)