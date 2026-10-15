package com.panomc.platform.error

import com.panomc.platform.model.Error

class LoginUserIsBanned(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("LOGIN_USER_IS_BANNED", 422, statusMessage, extras)