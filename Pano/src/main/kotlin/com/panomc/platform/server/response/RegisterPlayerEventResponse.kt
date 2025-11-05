package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class RegisterPlayerEventResponse(val error: String?) : ServerEventResponse()