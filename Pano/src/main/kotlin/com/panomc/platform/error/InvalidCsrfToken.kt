package com.panomc.platform.error

import com.panomc.platform.model.Error

/** A cookie-authenticated panel request that changes something came without its CSRF token. */
class InvalidCsrfToken(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(403, statusMessage, extras)
