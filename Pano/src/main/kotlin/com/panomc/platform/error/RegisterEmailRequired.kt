package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterEmailRequired(
    statusMessage: String = "Register email is required",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_EMAIL_REQUIRED", 403, statusMessage, extras)
