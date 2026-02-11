package com.panomc.platform.error

import com.panomc.platform.model.Error

class DisabledForDemo(
    statusMessage: String = "This action is disabled in demo mode.",
    extras: Map<String, Any?> = mapOf()
) : Error(401, statusMessage, extras)
