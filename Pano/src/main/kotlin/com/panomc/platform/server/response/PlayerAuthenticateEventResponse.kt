package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class PlayerAuthenticateEventResponse(val success: Boolean) : ServerEventResponse()