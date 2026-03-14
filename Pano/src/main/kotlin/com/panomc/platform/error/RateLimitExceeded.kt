package com.panomc.platform.error

import com.panomc.platform.model.Error

class RateLimitExceeded(
    statusMessage: String = "Too Many Requests",
    extras: Map<String, Any?> = mapOf()
) : Error(429, statusMessage, extras)
