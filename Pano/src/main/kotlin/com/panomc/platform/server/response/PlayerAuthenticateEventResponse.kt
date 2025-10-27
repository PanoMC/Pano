package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse
import java.util.*

data class PlayerAuthenticateEventResponse(override val eventId: UUID, val success: Boolean) : ServerEventResponse