package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterUsernameTooShort(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_USERNAME_TOO_SHORT", 422, statusMessage, extras)