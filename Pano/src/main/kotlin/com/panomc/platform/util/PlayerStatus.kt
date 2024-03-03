package com.panomc.platform.util

enum class PlayerStatus(val type: String, val value: Int) {
    ALL("all", 1),
    HAS_PERM("hasPerm", 2),
    BANNED("banned", 0);

    override fun toString(): String {
        return type
    }

    companion object {
        fun valueOf(type: String) = PlayerStatus.values().find { it.type == type }
    }
}