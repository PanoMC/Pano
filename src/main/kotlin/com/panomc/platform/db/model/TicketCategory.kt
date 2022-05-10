package com.panomc.platform.db.model

data class TicketCategory(val id: Int, val title: String, val description: String = "", val url: String = "")