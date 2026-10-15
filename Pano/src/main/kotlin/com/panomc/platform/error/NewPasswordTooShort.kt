package com.panomc.platform.error

import com.panomc.platform.model.Error

class NewPasswordTooShort(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NEW_PASSWORD_TOO_SHORT", 422, statusMessage, extras)