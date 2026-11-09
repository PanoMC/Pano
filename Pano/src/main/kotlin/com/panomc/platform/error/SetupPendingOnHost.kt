package com.panomc.platform.error

import com.panomc.platform.model.Error

/** Pano Host sets this site up by itself and has not finished yet; the setup wizard stays closed until it has. */
class SetupPendingOnHost(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("SETUP_PENDING_ON_HOST", 503, statusMessage, extras)
