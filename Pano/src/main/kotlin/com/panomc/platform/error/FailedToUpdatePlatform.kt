package com.panomc.platform.error

import com.panomc.platform.model.Error

class FailedToUpdatePlatform(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FAILED_TO_UPDATE_PLATFORM", 500, statusMessage, extras)