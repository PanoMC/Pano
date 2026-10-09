package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterInvalidUsername(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_INVALID_USERNAME", 422, statusMessage, extras)