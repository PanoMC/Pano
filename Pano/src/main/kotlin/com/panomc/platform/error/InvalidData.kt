package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidData(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_DATA", 422, statusMessage, extras)