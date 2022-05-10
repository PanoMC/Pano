package com.panomc.platform.util

object TextUtil {
    fun convertStringToURL(string: String) =
        string
            .replace("\\s+".toRegex(), "-")
            .replace("[^0-9A-Za-z-]+".toRegex(), "")
            .lowercase()
}