package com.panomc.platform.error

import com.panomc.platform.model.Error

class AlreadyBanned(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("ALREADY_BANNED", 422, statusMessage, extras)