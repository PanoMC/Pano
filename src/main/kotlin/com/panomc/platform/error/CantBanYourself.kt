package com.panomc.platform.error

import com.panomc.platform.model.Error

class CantBanYourself(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(401, statusMessage, extras)