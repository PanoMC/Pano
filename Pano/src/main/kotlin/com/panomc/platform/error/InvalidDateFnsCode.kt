package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidDateFnsCode(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_DATE_FNS_CODE", 422, statusMessage, extras)