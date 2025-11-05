package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

data class PlayerAuthenticateEventRequest(
    val username: String,
    val password: String
) : ServerEventRequest()