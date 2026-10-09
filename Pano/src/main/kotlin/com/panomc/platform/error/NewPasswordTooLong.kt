package com.panomc.platform.error

import com.panomc.platform.model.Error

class NewPasswordTooLong(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NEW_PASSWORD_TOO_LONG", 422, statusMessage, extras)