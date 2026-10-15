package com.panomc.platform.error

import com.panomc.platform.model.Error

class MaintenanceModeEnabled(
    statusMessage: String = "Service Unavailable",
    extras: Map<String, Any?> = mapOf()
) : Error("MAINTENANCE_MODE_ENABLED", 503, statusMessage, extras)
