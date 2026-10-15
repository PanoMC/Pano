package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterLinkCodeInvalid(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_LINK_CODE_INVALID", 422, statusMessage, extras)
