package com.panomc.platform.notification

enum class Notifications {
    ;

    enum class UserNotification(val typeId: String, val action: String) {
        AN_ADMIN_REPLIED_TICKET("AN_ADMIN_REPLIED_TICKET", "AN_ADMIN_REPLIED_TICKET"),
        AN_ADMIN_CLOSED_TICKET("AN_ADMIN_CLOSED_TICKET", "AN_ADMIN_CLOSED_TICKET")
    }

    enum class PanelNotification(val typeId: String, val action: String) {
        NEW_TICKET("NEW_TICKET", "NEW_TICKET"),
        NEW_TICKET_MESSAGE("NEW_TICKET_MESSAGE", "NEW_TICKET_MESSAGE"),
        TICKET_CLOSED_BY_USER("TICKET_CLOSED_BY_USER", "TICKET_CLOSED_BY_USER"),
        SERVER_CONNECT_REQUEST("SERVER_CONNECT_REQUEST", "SERVER_CONNECT_REQUEST")
    }
}