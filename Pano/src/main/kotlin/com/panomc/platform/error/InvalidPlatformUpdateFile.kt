package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidPlatformUpdateFile(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_PLATFORM_UPDATE_FILE", 500, statusMessage, extras)