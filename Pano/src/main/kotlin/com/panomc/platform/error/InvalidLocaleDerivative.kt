package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidLocaleDerivative(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_LOCALE_DERIVATIVE", 422, statusMessage, extras)