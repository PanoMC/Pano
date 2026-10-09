package com.panomc.platform.error

import com.panomc.platform.model.Error

class Unauthorized(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("UNAUTHORIZED", 403, statusMessage, extras)