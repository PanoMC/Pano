package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

data class GenerateLinkCodeEventRequest(val username: String) : ServerEventRequest()
