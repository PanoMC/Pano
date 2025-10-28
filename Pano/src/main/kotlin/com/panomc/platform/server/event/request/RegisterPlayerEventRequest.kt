package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import java.util.*

data class RegisterPlayerEventRequest(
    val eventId: UUID,
    val username: String,
    val password: String,
    val ipAddress: String
) : ServerEventRequest