package com.panomc.platform.util

enum class PostStatus(val status: String, val value: Int) {
    PUBLISHED("published", 1),
    DRAFT("draft", 2),
    TRASH("trash", 0);

    override fun toString(): String {
        return status
    }

    companion object {
        fun valueOf(status: String) = PostStatus.values().find { it.status == status }

        fun valueOf(value: Int) = PostStatus.values().find { it.value == value }
    }
}