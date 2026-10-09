package com.panomc.platform.error

import com.panomc.platform.model.Error

class LoginIsInvalid(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("LOGIN_IS_INVALID", 422, statusMessage, extras)