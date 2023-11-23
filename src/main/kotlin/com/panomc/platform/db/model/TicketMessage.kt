package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class TicketMessage(
    val id: Long = -1,
    val userId: Long,
    val ticketId: Long,
    val message: String,
    val date: Long = System.currentTimeMillis(),
    val panel: Int = 0
) : DBEntity()