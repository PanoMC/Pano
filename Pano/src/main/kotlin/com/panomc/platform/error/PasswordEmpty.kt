package com.panomc.platform.error

import com.panomc.platform.model.Error

class PasswordEmpty(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PASSWORD_EMPTY", 422, statusMessage, extras)