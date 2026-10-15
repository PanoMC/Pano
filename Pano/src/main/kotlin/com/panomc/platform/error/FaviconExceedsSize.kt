package com.panomc.platform.error

import com.panomc.platform.model.Error

class FaviconExceedsSize(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FAVICON_EXCEEDS_SIZE", 422, statusMessage, extras)