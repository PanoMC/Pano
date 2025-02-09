package com.panomc.platform.auth

import com.panomc.platform.util.TextUtil.convertToSnakeCase

open class PanelPermission(val iconName: String = "") {
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
        return this::class.java.simpleName.replaceLastUsingReverse("Permission", "").convertToSnakeCase().uppercase()
    }
}