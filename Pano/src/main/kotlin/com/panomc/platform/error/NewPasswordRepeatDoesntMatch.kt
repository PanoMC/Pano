package com.panomc.platform.error

import com.panomc.platform.model.Error

class NewPasswordRepeatDoesntMatch(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NEW_PASSWORD_REPEAT_DOESNT_MATCH", 422, statusMessage, extras)