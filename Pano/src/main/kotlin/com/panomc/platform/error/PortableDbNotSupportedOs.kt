package com.panomc.platform.error

import com.panomc.platform.model.Error

class PortableDbNotSupportedOs(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PORTABLE_DB_NOT_SUPPORTED_OS", 500, statusMessage, extras)
