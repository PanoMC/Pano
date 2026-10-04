package com.panomc.platform.notification.type.panel

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.PanelUserNotificationType

/**
 * A managed server's process exited without being asked to.
 *
 * Only a node can tell the difference between a stop somebody ordered and a crash, which is why
 * this notification exists for managed servers and has no linked-server equivalent.
 */
@NotificationDefinition
data class ServerCrashedNotification(
    val id: Long? = null,
    val serverName: String? = null,
    val exitCode: Int? = null,
    /** The console line that explains the crash, truncated; null when nothing in it looked like one. */
    val reason: String? = null
) : PanelUserNotificationType()
