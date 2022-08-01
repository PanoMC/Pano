package com.panomc.platform.util

object TextUtil {
    fun convertStringToUrl(string: String, limit: Int = 200) =
        string
            .replace("\\s+".toRegex(), "-")
            .replace("[^\\dA-Za-z-]+".toRegex(), "")
            .lowercase()
            .take(limit)
}