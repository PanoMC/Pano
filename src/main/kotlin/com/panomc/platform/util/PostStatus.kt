package com.panomc.platform.util

enum class PostStatus(val type: String, val value: Int) {
    PUBLISHED("published", 1),
    DRAFT("draft", 2),
    TRASH("trash", 0);

    override fun toString(): String {
        return type
    }

    companion object {
        fun valueOf(type: String) = PostStatus.values().find { it.type == type }
    }
}