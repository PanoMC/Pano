package com.panomc.platform.error

import com.panomc.platform.model.Error

class UsernameRequired(
    statusMessage: String = "Username is required",
    extras: Map<String, Any?> = mapOf()
) : Error(403, statusMessage, extras)
