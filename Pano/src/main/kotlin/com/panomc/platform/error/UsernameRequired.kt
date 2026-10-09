package com.panomc.platform.error

import com.panomc.platform.model.Error

class UsernameRequired(
    statusMessage: String = "Username is required",
    extras: Map<String, Any?> = mapOf()
) : Error("USERNAME_REQUIRED", 403, statusMessage, extras)
