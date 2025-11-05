package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class PongServerEventResponse(val text: String) : ServerEventResponse()