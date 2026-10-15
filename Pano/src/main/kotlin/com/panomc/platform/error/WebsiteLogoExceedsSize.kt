package com.panomc.platform.error

import com.panomc.platform.model.Error

class WebsiteLogoExceedsSize(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("WEBSITE_LOGO_EXCEEDS_SIZE", 422, statusMessage, extras)