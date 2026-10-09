package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterEmailEmpty(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_EMAIL_EMPTY", 422, statusMessage, extras)