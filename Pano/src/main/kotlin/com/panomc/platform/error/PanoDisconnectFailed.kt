package com.panomc.platform.error

import com.panomc.platform.model.Error

class PanoDisconnectFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(500, statusMessage, extras)