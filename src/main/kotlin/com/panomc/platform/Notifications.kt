package com.panomc.platform

enum class Notifications {
    ;

    enum class UserNotification(val typeId: String, val action: String) {
        AN_ADMIN_REPLIED_TICKET("AN_ADMIN_REPLIED_TICKET", "AN_ADMIN_REPLIED_TICKET")
    }
}