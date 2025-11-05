package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class IsPlayerRegisteredEventResponse(val registered: Boolean) : ServerEventResponse()