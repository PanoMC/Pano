package com.panomc.platform.db.model

data class Ticket(
    val id: Int,
    val title: String,
    val categoryID: Int,
    val userID: Int,
    val date: Long,
    val lastUpdate: Long,
    val status: Int
)