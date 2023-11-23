package com.panomc.platform.util

enum class TicketPageType(val ticketStatus: TicketStatus?) {
    ALL(null),
    WAITING_REPLY(TicketStatus.NEW),
    CLOSED(TicketStatus.CLOSED)
}