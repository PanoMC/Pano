package com.panomc.platform.notification.type.user

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.UserNotificationType

@NotificationDefinition
data class AnAdminClosedTicketNotification(
    val id: Long? = null,
    val admin: String? = null,
    val faIcon: String = "fas fa-ticket"
) : UserNotificationType()