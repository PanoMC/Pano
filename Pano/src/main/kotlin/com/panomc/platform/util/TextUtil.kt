package com.panomc.platform.util

import java.io.PrintWriter
import java.io.StringWriter

object TextUtil {
    /** Removes all Unicode whitespace, matching site theme login/register identifier fields. */
    fun stripWhitespace(s: String) = s.replace(Regex("\\s"), "")

    fun convertStringToUrl(string: String, limit: Int = 200) =
        string
            .replace("\\s+".toRegex(), "-")
            .replace("[^\\dA-Za-z-]+".toRegex(), "")
            .lowercase()
            .take(limit)

    fun String.convertToSnakeCase(): String {
        val regex = Regex("([a-z])([A-Z])")
        val result = regex.replace(this) { matchResult ->
            "${matchResult.groupValues[1]}_${matchResult.groupValues[2].lowercase()}"
        }
        return result
    }

    fun getStackTraceAsString(exception: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        exception.printStackTrace(printWriter)
        return stringWriter.toString()
    }

    fun isValidLanguageTag(tag: String): Boolean {
        val bcp47Regex = Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$")
        return bcp47Regex.matches(tag)
    }

    fun maskEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return email

        val user = parts[0]
        val host = parts[1]

        val maskedUser = if (user.length > 1) {
            user.take(1) + "***"
        } else {
            user + "***"
        }

        val hostParts = host.split(".")
        val maskedHost = if (hostParts.size >= 2) {
            val domain = hostParts[0]
            val extension = hostParts.drop(1).joinToString(".")
            (if (domain.length > 1) domain.take(1) else domain) + "***." + extension
        } else {
            if (host.length > 1) host.take(1) + "***" else host + "***"
        }

        return "$maskedUser@$maskedHost"
    }
}