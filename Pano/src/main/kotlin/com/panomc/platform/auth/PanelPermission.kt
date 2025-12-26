package com.panomc.platform.auth

import com.panomc.platform.util.TextUtil.convertToSnakeCase

open class PanelPermission(iconName: String = "") : Permission(iconName) {
    override fun toString(): String {
        val rawName = this::class.java.simpleName.replace("Permission", "")
        val nodeName = rawName
            .convertToSnakeCase()
            .lowercase()
            .replace("_", ".")

        return "pano.panel.$nodeName"
    }
}