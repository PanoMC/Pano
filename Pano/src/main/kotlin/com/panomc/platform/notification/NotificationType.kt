package com.panomc.platform.notification

import com.panomc.platform.util.TextUtil.convertToSnakeCase

interface NotificationType {
    fun getName() = javaClass.simpleName.replace("Notification", "").convertToSnakeCase().uppercase()
}