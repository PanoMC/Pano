package com.panomc.platform.db.model

data class TicketMessage(
    val id: Int,
    val userId: Int,
    val ticketId: Int,
    val message: String,
    val date: Long,
    val panel: Int
)