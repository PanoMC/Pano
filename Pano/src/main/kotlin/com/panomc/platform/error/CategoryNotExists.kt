package com.panomc.platform.error

import com.panomc.platform.model.Error

class CategoryNotExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CATEGORY_NOT_EXISTS", 404, statusMessage, extras)