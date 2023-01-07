package com.panomc.platform.auth

enum class PanelPermission {
    ACCESS_PANEL,
    MANAGE_SERVERS,
    MANAGE_POSTS,
    MANAGE_TICKETS,
    MANAGE_PLAYERS,
    MANAGE_VIEW,
    MANAGE_ADDON,
    MANAGE_PLATFORM_SETTINGS;

    override fun toString(): String {
        return super.toString().lowercase()
    }
}