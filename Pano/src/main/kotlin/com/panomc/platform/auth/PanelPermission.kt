package com.panomc.platform.auth

enum class PanelPermission {
    ACCESS_PANEL,
    MANAGE_SERVERS,
    MANAGE_POSTS,
    MANAGE_TICKETS,
    MANAGE_PLAYERS,
    MANAGE_VIEW,
    MANAGE_ADDONS,
    MANAGE_PLATFORM_SETTINGS,
    MANAGE_PERMISSION_GROUPS;

    override fun toString(): String {
        return super.toString().lowercase()
    }
}