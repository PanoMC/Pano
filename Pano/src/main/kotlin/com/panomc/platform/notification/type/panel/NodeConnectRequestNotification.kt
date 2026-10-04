package com.panomc.platform.notification.type.panel

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.PanelUserNotificationType

/**
 * A node daemon paired with the rotating code and is waiting to be let in.
 *
 * Sent to everyone who can manage nodes, exactly like a server connect request: until someone
 * accepts it, the node cannot open its socket and nothing runs on it.
 */
@NotificationDefinition
data class NodeConnectRequestNotification(
    val id: Long? = null,
    val nodeName: String? = null
) : PanelUserNotificationType()
