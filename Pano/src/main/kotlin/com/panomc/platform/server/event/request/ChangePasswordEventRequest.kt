package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

data class ChangePasswordEventRequest(
    val username: String,
    val password: String,
    val passwordRepeat: String,
    val admin: String?,
    val console: Boolean,
    /** The player's current password (plugins with `password-check`). */
    val currentPassword: String? = null,
    /** A staff / console reset, where no current password exists (plugins with `password-check`). */
    val reset: Boolean? = null
) : ServerEventRequest()