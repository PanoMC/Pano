package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import java.util.*

data class GetServerSettingsEventRequest(
    val eventId: UUID,
) : ServerEventRequest