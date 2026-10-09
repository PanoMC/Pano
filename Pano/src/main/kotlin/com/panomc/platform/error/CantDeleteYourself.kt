package com.panomc.platform.error

import com.panomc.platform.model.Error

class CantDeleteYourself(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CANT_DELETE_YOURSELF", 422, statusMessage, extras)