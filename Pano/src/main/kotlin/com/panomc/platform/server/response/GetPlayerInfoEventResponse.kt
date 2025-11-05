package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class GetPlayerInfoEventResponse(
    val registered: Boolean,
    val banned: Boolean,
    val verified: Boolean,
    val pendingEmail: String? = null,
    val locale: String? = null,
) : ServerEventResponse()