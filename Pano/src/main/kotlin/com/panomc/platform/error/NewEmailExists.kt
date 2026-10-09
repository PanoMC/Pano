package com.panomc.platform.error

import com.panomc.platform.model.Error

class NewEmailExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NEW_EMAIL_EXISTS", 422, statusMessage, extras)