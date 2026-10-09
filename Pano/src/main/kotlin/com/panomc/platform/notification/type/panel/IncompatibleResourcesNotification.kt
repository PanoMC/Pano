package com.panomc.platform.notification.type.panel

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.PanelUserNotificationType

/**
 * Plugins or themes the API level gate refused and the boot reconcile could not replace (doc 04 section 7).
 * One per run that leaves something refused; [count] is how many resources are still refused.
 */
@NotificationDefinition
data class IncompatibleResourcesNotification(
    val count: Int? = null,
    val resources: List<String>? = null
) : PanelUserNotificationType()
