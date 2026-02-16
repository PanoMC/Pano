package com.panomc.platform.server.event.response

import com.panomc.platform.server.ServerEventResponse

data class GenerateLinkCodeEventResponse(val code: String) : ServerEventResponse()
