package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterUsernameNotAvailable(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_USERNAME_NOT_AVAILABLE", 422, statusMessage, extras)