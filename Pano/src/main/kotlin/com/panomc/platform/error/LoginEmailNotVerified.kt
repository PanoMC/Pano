package com.panomc.platform.error

import com.panomc.platform.model.Error

class LoginEmailNotVerified(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("LOGIN_EMAIL_NOT_VERIFIED", 422, statusMessage, extras)