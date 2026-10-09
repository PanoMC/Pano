package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidIpAddress(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_IP_ADDRESS", 422, statusMessage, extras)