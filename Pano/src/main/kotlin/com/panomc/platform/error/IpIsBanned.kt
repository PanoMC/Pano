package com.panomc.platform.error

import com.panomc.platform.model.Error

class IpIsBanned(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("IP_IS_BANNED", 422, statusMessage, extras)
