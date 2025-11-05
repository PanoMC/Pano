package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

data class ChangePasswordEventRequest(
    val username: String,
    val password: String,
    val passwordRepeat: String,
    val admin: String?,
    val console: Boolean
) : ServerEventRequest()