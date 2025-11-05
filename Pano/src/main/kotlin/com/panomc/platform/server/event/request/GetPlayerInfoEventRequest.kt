package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

data class GetPlayerInfoEventRequest(
    val username: String
) : ServerEventRequest()