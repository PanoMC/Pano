package com.panomc.platform.error

import com.panomc.platform.model.Error

class NewPasswordEmpty(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NEW_PASSWORD_EMPTY", 422, statusMessage, extras)