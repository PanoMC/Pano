package com.panomc.platform.util

object TextUtil {
    fun convertStringToUrl(string: String) =
        string
            .replace("\\s+".toRegex(), "-")
            .replace("[^\\dA-Za-z-]+".toRegex(), "")
            .lowercase()
}