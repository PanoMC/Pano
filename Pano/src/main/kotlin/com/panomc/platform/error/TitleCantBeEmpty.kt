package com.panomc.platform.error

import com.panomc.platform.model.Error

class TitleCantBeEmpty(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("TITLE_CANT_BE_EMPTY", 422, statusMessage, extras)