package com.panomc.platform.error

import com.panomc.platform.model.Error

class BadRequest(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("BAD_REQUEST", 400, statusMessage, extras)