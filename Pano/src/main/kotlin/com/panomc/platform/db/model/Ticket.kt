package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.TicketStatus

data class Ticket(
    val id: Long = -1,
    val title: String,
    val categoryId: Long = -1,
    val userId: Long,
    val date: Long = System.currentTimeMillis(),
    val lastUpdate: Long = System.currentTimeMillis(),
    val status: TicketStatus = TicketStatus.NEW
) : DBEntity()