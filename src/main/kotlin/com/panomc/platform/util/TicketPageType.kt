package com.panomc.platform.util

enum class TicketPageType(val type: String, val value: Int) {
    ALL("all", 2),
    WAITING_REPLY("waitingReply", 1),
    CLOSED("closed", 3);

    override fun toString(): String {
        return type
    }

    companion object {
        fun valueOf(type: String) = values().find { it.type == type }
    }
}