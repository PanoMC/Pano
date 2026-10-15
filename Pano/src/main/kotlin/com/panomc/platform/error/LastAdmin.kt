package com.panomc.platform.error

import com.panomc.platform.model.Error

class LastAdmin(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("LAST_ADMIN", 422, statusMessage, extras)