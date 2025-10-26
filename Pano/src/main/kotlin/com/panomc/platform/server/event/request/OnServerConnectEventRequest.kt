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
    val motd: String?
) : ServerEventRequest