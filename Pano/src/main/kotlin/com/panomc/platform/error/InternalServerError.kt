package com.panomc.platform.error

import com.panomc.platform.model.Error

class InternalServerError(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(statusMessage = statusMessage, extras = extras)