package com.panomc.platform.error

import com.panomc.platform.model.Error

class CantResetPasswordWait5Minutes(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CANT_RESET_PASSWORD_WAIT5MINUTES", 422, statusMessage, extras)
