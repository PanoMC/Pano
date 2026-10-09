package com.panomc.platform.error

import com.panomc.platform.model.Error

class FaviconWrongContentType(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FAVICON_WRONG_CONTENT_TYPE", 422, statusMessage, extras)