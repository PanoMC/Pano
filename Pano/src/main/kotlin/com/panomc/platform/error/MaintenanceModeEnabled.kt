package com.panomc.platform.error

import com.panomc.platform.model.Error

class MaintenanceModeEnabled(
    statusMessage: String = "Service Unavailable",
    extras: Map<String, Any?> = mapOf()
) : Error(503, statusMessage, extras)
