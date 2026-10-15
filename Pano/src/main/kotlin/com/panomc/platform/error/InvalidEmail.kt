package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidEmail(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_EMAIL", 422, statusMessage, extras)