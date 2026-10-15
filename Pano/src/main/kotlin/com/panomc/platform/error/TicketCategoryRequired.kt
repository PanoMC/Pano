package com.panomc.platform.error

import com.panomc.platform.model.Error

/** The website does not take tickets without a category, and there is a category to pick. */
class TicketCategoryRequired(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("TICKET_CATEGORY_REQUIRED", 422, statusMessage, extras)
