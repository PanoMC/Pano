package com.panomc.platform.error

import com.panomc.platform.model.Error

class FailedToInstallResource(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FAILED_TO_INSTALL_RESOURCE", 500, statusMessage, extras)