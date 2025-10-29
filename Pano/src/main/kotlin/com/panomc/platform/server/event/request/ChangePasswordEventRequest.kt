package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import java.util.*

data class ChangePasswordEventRequest(
    val eventId: UUID,
    val username: String,
    val password: String,
) : ServerEventRequest