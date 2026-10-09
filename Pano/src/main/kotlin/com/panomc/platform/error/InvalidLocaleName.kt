package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidLocaleName(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_LOCALE_NAME", 422, statusMessage, extras)