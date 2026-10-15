package com.panomc.platform.error

import com.panomc.platform.model.Error

class PanoConnectFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_CONNECT_FAILED", 400, statusMessage, extras)