package com.panomc.platform.db.model

data class TicketMessage(
    val id: Int,
    val userID: Int,
    val ticketID: Int,
    val message: String,
    val date: Long,
    val panel: Int
)