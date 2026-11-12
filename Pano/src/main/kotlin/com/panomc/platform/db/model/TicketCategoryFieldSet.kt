package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * `ticket_category_field_set`: the custom fields of one ticket category, as the JSON array of field
 * definitions (`ticket.TicketFields`). A category without a row has no fields.
 */
data class TicketCategoryFieldSet(
    val categoryId: Long,
    val fields: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
