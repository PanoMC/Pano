package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class GetPlayerInfoEventResponse(
    val registered: Boolean,
    val banned: Boolean,
    val banReason: String? = null,
    val bannedUntil: Long? = null,
    val verified: Boolean,
    val email: String? = null,
    val locale: String? = null,
) : ServerEventResponse()