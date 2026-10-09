package com.panomc.platform.error

import com.panomc.platform.model.Error

class NotBanned(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NOT_BANNED", 422, statusMessage, extras)