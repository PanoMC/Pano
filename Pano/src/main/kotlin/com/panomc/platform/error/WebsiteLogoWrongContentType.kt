package com.panomc.platform.error

import com.panomc.platform.model.Error

class WebsiteLogoWrongContentType(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("WEBSITE_LOGO_WRONG_CONTENT_TYPE", 422, statusMessage, extras)