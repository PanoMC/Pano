package com.panomc.platform.error

import com.panomc.platform.model.Error

class SomeTicketsArentExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("SOME_TICKETS_ARENT_EXISTS", 404, statusMessage, extras)