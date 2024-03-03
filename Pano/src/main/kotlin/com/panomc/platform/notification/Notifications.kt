package com.panomc.platform.notification

enum class Notifications {
    ;

    enum class UserNotificationType {
        AN_ADMIN_REPLIED_TICKET,
        AN_ADMIN_CLOSED_TICKET
    }

    enum class PanelNotificationType {
        NEW_TICKET,
        NEW_TICKET_MESSAGE,
        TICKET_CLOSED_BY_USER,
        SERVER_CONNECT_REQUEST
    }
}