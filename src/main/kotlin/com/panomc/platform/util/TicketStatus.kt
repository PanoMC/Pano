package com.panomc.platform.util

enum class TicketStatus(val status: String, val value: Int) {
    ALL("all", 2),
    WAITING_REPLY("waitingReply", 1),
    CLOSED("closed", 3);

    override fun toString(): String {
        return status
    }

    companion object {
        fun valueOf(status: String) = values().find { it.status == status }
        fun valueOf(value: Int) = values().find { it.value == value }
    }
}