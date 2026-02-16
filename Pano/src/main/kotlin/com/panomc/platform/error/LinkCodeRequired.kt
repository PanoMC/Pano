package com.panomc.platform.error

import com.panomc.platform.model.Error

class LinkCodeRequired(
    statusMessage: String = "Link code is required",
    extras: Map<String, Any?> = mapOf()
) : Error(403, statusMessage, extras)
