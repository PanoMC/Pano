package com.panomc.platform.error

import com.panomc.platform.model.Error

class LocaleAlreadyExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("LOCALE_ALREADY_EXISTS", 409, statusMessage, extras)
