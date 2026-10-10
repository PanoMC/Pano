package com.panomc.platform.error

import com.panomc.platform.model.Error

/** Pano Mail needs a connected panomc.com account and this Pano has none. */
class PanoMailConnectRequired(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CONNECT_REQUIRED", 409, statusMessage, extras)
