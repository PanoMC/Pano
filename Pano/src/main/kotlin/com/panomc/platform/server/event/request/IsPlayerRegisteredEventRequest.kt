package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

data class IsPlayerRegisteredEventRequest(
    val username: String
) : ServerEventRequest()