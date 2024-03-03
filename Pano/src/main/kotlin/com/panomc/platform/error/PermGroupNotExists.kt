package com.panomc.platform.error

import com.panomc.platform.model.Error

class PermGroupNotExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(404, statusMessage, extras)