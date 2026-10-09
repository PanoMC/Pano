package com.panomc.platform.error

import com.panomc.platform.model.Error

class InternalServerError(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INTERNAL_SERVER_ERROR", statusMessage = statusMessage, extras = extras)