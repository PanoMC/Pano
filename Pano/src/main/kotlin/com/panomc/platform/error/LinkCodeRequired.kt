package com.panomc.platform.error

import com.panomc.platform.model.Error

class LinkCodeRequired(
    statusMessage: String = "Link code is required",
    extras: Map<String, Any?> = mapOf()
) : Error("LINK_CODE_REQUIRED", 403, statusMessage, extras)
