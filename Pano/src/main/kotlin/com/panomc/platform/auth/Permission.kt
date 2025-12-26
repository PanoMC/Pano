package com.panomc.platform.auth

import com.panomc.platform.util.TextUtil.convertToSnakeCase

open class Permission(val iconName: String = "") {
    private fun String.replaceLastUsingReverse(
        oldValue: String,
        newValue: String,
        ignoreCase: Boolean = false
    ): String {
        return this.reversed()
            .replaceFirst(oldValue.reversed(), newValue.reversed(), ignoreCase)
            .reversed()
    }

    override fun toString(): String {
        val rawName = this::class.java.simpleName.replaceLastUsingReverse("Permission", "")
        val nodeName = rawName
            .convertToSnakeCase()
            .lowercase()
            .replace("_", ".")

        return "pano.$nodeName"
    }
}