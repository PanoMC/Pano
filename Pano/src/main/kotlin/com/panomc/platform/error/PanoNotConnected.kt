package com.panomc.platform.error

import com.panomc.platform.model.Error

class PanoNotConnected(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_NOT_CONNECTED", 400, statusMessage, extras)