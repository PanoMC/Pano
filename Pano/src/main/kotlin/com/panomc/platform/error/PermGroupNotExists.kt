package com.panomc.platform.error

import com.panomc.platform.model.Error

class PermGroupNotExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PERM_GROUP_NOT_EXISTS", 404, statusMessage, extras)