package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterUsernameEmpty(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_USERNAME_EMPTY", 422, statusMessage, extras)