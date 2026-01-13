package com.panomc.platform.auth

open class PanelPermission(iconName: String = "") : Permission(iconName) {
    override fun toString(): String {
        val nodeName = key.lowercase().replace("_", ".")

        if (source != null && source != "platform") {
            return "pano.plugin.$source.$nodeName"
        }

        return "pano.panel.$nodeName"
    }
}