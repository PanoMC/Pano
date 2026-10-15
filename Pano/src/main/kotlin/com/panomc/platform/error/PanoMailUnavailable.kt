package com.panomc.platform.error

import com.panomc.platform.model.Error

/** The website refused Pano Mail or could not be reached; `extras.reason` says why. */
class PanoMailUnavailable(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_MAIL_UNAVAILABLE", 502, statusMessage, extras)
