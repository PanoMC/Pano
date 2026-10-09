package com.panomc.platform.error

import com.panomc.platform.model.Error

class NotFound(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NOT_FOUND", 404, statusMessage, extras)