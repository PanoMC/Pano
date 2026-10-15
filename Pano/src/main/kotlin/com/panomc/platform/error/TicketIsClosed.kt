package com.panomc.platform.error

import com.panomc.platform.model.Error

class TicketIsClosed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("TICKET_IS_CLOSED", 422, statusMessage, extras)