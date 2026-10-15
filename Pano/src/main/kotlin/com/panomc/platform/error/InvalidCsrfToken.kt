package com.panomc.platform.error

import com.panomc.platform.model.Error

/** A cookie-authenticated panel request that changes something came without its CSRF token. */
class InvalidCsrfToken(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("INVALID_CSRF_TOKEN", 403, statusMessage, extras)
