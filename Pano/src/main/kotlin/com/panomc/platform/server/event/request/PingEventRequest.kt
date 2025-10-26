package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import java.util.*

data class PingEventRequest(val eventId: UUID) : ServerEventRequest