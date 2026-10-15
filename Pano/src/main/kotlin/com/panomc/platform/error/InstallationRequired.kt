package com.panomc.platform.error

import com.panomc.platform.model.Error

class InstallationRequired(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INSTALLATION_REQUIRED", 401, statusMessage, extras)