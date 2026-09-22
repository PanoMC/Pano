package com.panomc.platform.notification.type.panel

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.PanelUserNotificationType

/**
 * The panel notifications raised by `AlertManager`, one class per alert kind.
 *
 * One class rather than one generic "alert" notification because the panel routes and translates
 * by type: a node going offline opens `/servers/nodes`, a server crashing opens that server. Every
 * one of them therefore carries both ids and both names — the id the panel navigates with, the
 * name it shows — even when only one of the two applies, so the routing rule is the same for all
 * of them and never has to fall back to a lookup.
 */
@NotificationDefinition
data class NodeOfflineNotification(
    val nodeId: Long? = null,
    val nodeName: String? = null,
    val serverId: Long? = null,
    val serverName: String? = null,
    /** How many of that node's servers Pano can no longer reach. */
    val serverCount: Int = 0
) : PanelUserNotificationType()

@NotificationDefinition
data class BackupFailedNotification(
    val serverId: Long? = null,
    val serverName: String? = null,
    val nodeId: Long? = null,
    val nodeName: String? = null,
    val error: String? = null
) : PanelUserNotificationType()

@NotificationDefinition
data class DiskLowNotification(
    val nodeId: Long? = null,
    val nodeName: String? = null,
    val serverId: Long? = null,
    val serverName: String? = null,
    /** Percent of the data disk in use, rounded, so the panel needs no arithmetic. */
    val usedPercent: Int = 0,
    val freeBytes: Long = 0
) : PanelUserNotificationType()

@NotificationDefinition
data class TpsLowNotification(
    val serverId: Long? = null,
    val serverName: String? = null,
    val nodeId: Long? = null,
    val nodeName: String? = null,
    /** One decimal place, as a string, so no locale turns 12.4 into 124. */
    val tps: String? = null
) : PanelUserNotificationType()

@NotificationDefinition
data class ScheduleFailedNotification(
    val serverId: Long? = null,
    val serverName: String? = null,
    val nodeId: Long? = null,
    val nodeName: String? = null,
    val scheduleName: String? = null,
    val error: String? = null
) : PanelUserNotificationType()

@NotificationDefinition
data class PluginUpdatesNotification(
    val serverId: Long? = null,
    val serverName: String? = null,
    /** How many plugins on this server have a newer build. */
    val count: Int = 0,
    /** The first few plugin names, so the notification says something without opening the page. */
    val names: List<String> = emptyList()
) : PanelUserNotificationType()
