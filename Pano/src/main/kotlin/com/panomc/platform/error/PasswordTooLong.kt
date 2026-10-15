package com.panomc.platform.error

import com.panomc.platform.model.Error

class PasswordTooLong(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PASSWORD_TOO_LONG", 422, statusMessage, extras)