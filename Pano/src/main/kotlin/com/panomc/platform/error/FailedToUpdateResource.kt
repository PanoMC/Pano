package com.panomc.platform.error

import com.panomc.platform.model.Error

class FailedToUpdateResource(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FAILED_TO_UPDATE_RESOURCE", 500, statusMessage, extras)