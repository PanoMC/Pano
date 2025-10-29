package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse
import java.util.*

data class ChangePasswordEventResponse(override val eventId: UUID, val error: String?) : ServerEventResponse