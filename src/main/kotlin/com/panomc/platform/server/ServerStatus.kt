package com.panomc.platform.server

enum class ServerStatus(val value: Int) {
    ONLINE(1),
    OFFLINE(0);

    companion object {
        fun valueOf(value: Int) = ServerStatus.values().find { it.value == value }
    }
}