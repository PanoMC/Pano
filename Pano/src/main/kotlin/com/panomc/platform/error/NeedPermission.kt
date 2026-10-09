package com.panomc.platform.error

import com.panomc.platform.model.Error

class NeedPermission(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NEED_PERMISSION", 401, statusMessage, extras)