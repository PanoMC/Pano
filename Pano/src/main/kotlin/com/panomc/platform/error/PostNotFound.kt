package com.panomc.platform.error

import com.panomc.platform.model.Error

class PostNotFound(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("POST_NOT_FOUND", 404, statusMessage, extras)