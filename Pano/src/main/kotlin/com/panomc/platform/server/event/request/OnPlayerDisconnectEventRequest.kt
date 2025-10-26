package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.dto.PlayerData

data class OnPlayerDisconnectEventRequest(val player: PlayerData, val playerCount: Int) : ServerEventRequest

