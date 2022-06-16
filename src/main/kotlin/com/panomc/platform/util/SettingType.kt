package com.panomc.platform.util

enum class SettingType(val type: String, val value: Int) {
    GENERAL("general", 0),
    WEBSITE("WEBSITE", 1);

    override fun toString(): String {
        return type
    }

    companion object {
        fun valueOf(type: String?) = values().find { it.type == type }
        fun valueOf(value: Int) = values().find { it.value == value }
    }
}