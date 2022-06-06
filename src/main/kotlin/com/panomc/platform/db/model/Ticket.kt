package com.panomc.platform.db.model

data class Ticket(
    val id: Int,
    val title: String,
    val categoryId: Int,
    val userId: Int,
    val date: Long,
    val lastUpdate: Long,
    val status: Int
)