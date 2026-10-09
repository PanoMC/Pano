package com.panomc.platform.error

import com.panomc.platform.model.Error

class FailedToInstallSystemResource(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FAILED_TO_INSTALL_SYSTEM_RESOURCE", 400, statusMessage, extras)