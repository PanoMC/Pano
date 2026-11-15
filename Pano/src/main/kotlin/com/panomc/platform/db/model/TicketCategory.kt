package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class TicketCategory(
    val id: Long = -1,
    val title: String = "-",
    val description: String = "",
    val url: String = "-",
    /** Off: its tickets stay, no new ticket can be opened in it. */
    val enabled: Boolean = true,
    /** The template sent by itself as the first answer of a new ticket in this category; -1 for none. */
    val autoReplyTemplateId: Long = -1,
    /** What the "new ticket" form shows in this category, a [com.panomc.platform.ticket.TicketForm] as JSON; empty = all. */
    val form: String? = null
) : DBEntity()