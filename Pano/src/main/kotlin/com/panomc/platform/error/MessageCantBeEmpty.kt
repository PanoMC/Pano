package com.panomc.platform.error

import com.panomc.platform.model.Error

class MessageCantBeEmpty(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("MESSAGE_CANT_BE_EMPTY", 422, statusMessage, extras)