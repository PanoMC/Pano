package com.panomc.platform.error

import com.panomc.platform.model.Error

class PageNotFound(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PAGE_NOT_FOUND", 404, statusMessage, extras)