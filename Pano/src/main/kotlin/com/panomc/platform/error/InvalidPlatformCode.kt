package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidPlatformCode(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_PLATFORM_CODE", 422, statusMessage, extras)