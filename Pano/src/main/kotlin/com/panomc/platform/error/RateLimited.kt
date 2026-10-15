package com.panomc.platform.error

import com.panomc.platform.model.Error

class RateLimited(
    statusMessage: String = "RATE_LIMITED",
    extras: Map<String, Any?> = mapOf()
) : Error("RATE_LIMITED", 429, statusMessage, extras)
