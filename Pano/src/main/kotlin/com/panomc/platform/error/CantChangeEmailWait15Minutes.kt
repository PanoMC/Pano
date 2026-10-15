package com.panomc.platform.error

import com.panomc.platform.model.Error

class CantChangeEmailWait15Minutes(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CANT_CHANGE_EMAIL_WAIT15MINUTES", 422, statusMessage, extras)