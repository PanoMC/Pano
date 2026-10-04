package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.ServerType

data class OnServerConnectEventRequest(
    val serverName: String,
    val playerCount: Long,
    val maxPlayerCount: Long,
    val serverType: ServerType,
    val serverVersion: String,
    val host: String,
    val port: Int,
    val startTime: Long,
    val favicon: String?,
    val motd: String?,
    val protocolVersion: Int? = null,
    val pluginVersion: String? = null,
    val capabilities: List<String>? = null,
    /**
     * The game JVM's IANA time zone id (`ZoneId.systemDefault().id`), preferred over the node's
     * host zone when both exist (SM-60, §2.4.25). Null from an older plugin.
     */
    val timeZone: String? = null
) : ServerEventRequest()