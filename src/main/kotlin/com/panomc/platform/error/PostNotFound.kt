package com.panomc.platform.error

import com.panomc.platform.model.Error

class PostNotFound(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(404, statusMessage, extras)