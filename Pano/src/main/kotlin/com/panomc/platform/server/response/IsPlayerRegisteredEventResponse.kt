package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse
import java.util.*

data class IsPlayerRegisteredEventResponse(override val eventId: UUID, val registered: Boolean) : ServerEventResponse