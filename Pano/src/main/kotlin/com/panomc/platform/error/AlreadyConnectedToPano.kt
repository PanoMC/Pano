package com.panomc.platform.error

import com.panomc.platform.model.Error

class AlreadyConnectedToPano(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("ALREADY_CONNECTED_TO_PANO", 400, statusMessage, extras)