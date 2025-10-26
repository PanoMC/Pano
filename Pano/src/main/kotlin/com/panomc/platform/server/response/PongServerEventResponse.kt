package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse
import java.util.*

data class PongServerEventResponse(override val eventId: UUID, val text: String) : ServerEventResponse