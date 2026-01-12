package com.panomc.platform.auth

import com.panomc.platform.util.TextUtil.convertToSnakeCase

open class Permission(val iconName: String = "") {
    internal var source: String? = null
    private fun String.replaceLastUsingReverse(
        oldValue: String,
        newValue: String,
        ignoreCase: Boolean = false
    ): String {
        return this.reversed()
            .replaceFirst(oldValue.reversed(), newValue.reversed(), ignoreCase)
            .reversed()
    }

    val key: String by lazy {
        this::class.java.simpleName
            .replaceLastUsingReverse("Permission", "")
            .convertToSnakeCase()
            .uppercase()
    }

    override fun toString(): String {
        val nodeName = key.lowercase().replace("_", ".")

        return "pano.$nodeName"
    }
}