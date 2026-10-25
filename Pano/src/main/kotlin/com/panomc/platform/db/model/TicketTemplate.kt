package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * A ready reply for tickets (`ticket_template`): an admin inserts it into a reply, or a category sends it by itself
 * as the first answer of a new ticket. [body] is the editor's HTML with `{{variables}}` (`TicketTemplateRenderer`).
 */
data class TicketTemplate(
    val id: Long = -1,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
