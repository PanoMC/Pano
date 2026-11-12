package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * `ticket_field_set`: the field values of one ticket, as the JSON snapshot array taken when it was opened
 * (`ticket.TicketFields.snapshot`: label, definition and value of every field that was visible). A ticket
 * without a row has no fields (opened in a category without fields, or before fields existed).
 */
data class TicketFieldSet(
    val ticketId: Long,
    val fields: String = "[]",
    /** The readable values, one per line: what the panel's ticket search looks in. */
    val searchText: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
