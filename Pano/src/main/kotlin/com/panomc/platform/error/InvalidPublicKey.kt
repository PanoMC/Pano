package com.panomc.platform.error

import com.panomc.platform.model.Error

class InvalidPublicKey(
    statusMessage: String = "",
    extras: Map<String, Any> = mapOf()
) : Error("INVALID_PUBLIC_KEY", 422, statusMessage, extras)