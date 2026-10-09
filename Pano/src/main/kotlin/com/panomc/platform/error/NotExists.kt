package com.panomc.platform.error

import com.panomc.platform.model.Error

class NotExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NOT_EXISTS", 404, statusMessage, extras)