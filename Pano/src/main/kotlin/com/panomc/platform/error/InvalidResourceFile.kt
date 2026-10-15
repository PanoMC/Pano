package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidResourceFile(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_RESOURCE_FILE", 400, statusMessage, extras)