package com.panomc.platform.error

import com.panomc.platform.model.Error

class RegisterNotAcceptedAgreement(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("REGISTER_NOT_ACCEPTED_AGREEMENT", 422, statusMessage, extras)