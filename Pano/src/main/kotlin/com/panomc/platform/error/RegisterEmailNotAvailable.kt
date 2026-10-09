package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterEmailNotAvailable(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_EMAIL_NOT_AVAILABLE", 422, statusMessage, extras)