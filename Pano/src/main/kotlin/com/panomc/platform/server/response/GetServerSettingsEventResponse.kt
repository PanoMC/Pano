package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse
import java.util.*

data class GetServerSettingsEventResponse(
    override val eventId: UUID,
    val authIntegration: Boolean,
    val banIntegration: Boolean,
    val permissionIntegration: Boolean
) : ServerEventResponse