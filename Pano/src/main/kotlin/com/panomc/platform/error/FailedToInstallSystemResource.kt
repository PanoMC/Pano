package com.panomc.platform.error

import com.panomc.platform.model.Error

class FailedToInstallSystemResource(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)