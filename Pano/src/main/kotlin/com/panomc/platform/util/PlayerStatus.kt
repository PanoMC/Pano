package com.panomc.platform.util

enum class PlayerStatus {
    ALL,
    HAS_PERM,
    BANNED;

    override fun toString(): String {
        return this.name
    }
}