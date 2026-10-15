package com.panomc.platform.error

import com.panomc.platform.model.Error

class PasswordTooShort(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PASSWORD_TOO_SHORT", 422, statusMessage, extras)