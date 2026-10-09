package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterPasswordAndPasswordRepeatNotSame(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_PASSWORD_AND_PASSWORD_REPEAT_NOT_SAME", 422, statusMessage, extras)