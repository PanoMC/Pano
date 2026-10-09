package com.panomc.platform.error

import com.panomc.platform.model.Error

class AlreadyIpBanned(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("ALREADY_IP_BANNED", 422, statusMessage, extras)
