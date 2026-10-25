package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class TicketMessage(
    val id: Long = -1,
    val userId: Long,
    val ticketId: Long,
    val message: String,
    val date: Long = System.currentTimeMillis(),
    val panel: Int = 0,
    /** Sent by the site itself (a category's auto-reply), not by a person: [userId] is -1. */
    val system: Boolean = false
) : DBEntity()