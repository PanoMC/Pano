package com.panomc.platform.notification.type.panel

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.PanelUserNotificationType

@NotificationDefinition
data class PanoUpdateFoundNotification(
    val version: String? = null
) : PanelUserNotificationType()