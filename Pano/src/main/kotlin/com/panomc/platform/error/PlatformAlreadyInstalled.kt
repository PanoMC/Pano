package com.panomc.platform.error

import com.panomc.platform.model.Error

class PlatformAlreadyInstalled(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PLATFORM_ALREADY_INSTALLED", 422, statusMessage, extras)