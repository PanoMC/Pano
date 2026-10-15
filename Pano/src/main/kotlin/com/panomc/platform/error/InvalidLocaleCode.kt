package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidLocaleCode(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_LOCALE_CODE", 422, statusMessage, extras)