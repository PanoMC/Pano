package com.panomc.platform.error

import com.panomc.platform.model.Error

class PanoDisconnectFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_DISCONNECT_FAILED", 500, statusMessage, extras)