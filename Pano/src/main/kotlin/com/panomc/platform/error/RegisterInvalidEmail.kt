package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterInvalidEmail(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_INVALID_EMAIL", 422, statusMessage, extras)