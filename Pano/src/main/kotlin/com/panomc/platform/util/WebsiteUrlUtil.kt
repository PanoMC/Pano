package com.panomc.platform.util

import java.net.URI

/**
 * Parsing helpers for the `website-url` config value.
 *
 * Normalization is deliberately conservative: only default ports (`:80` on http, `:443` on
 * https) are dropped, because a non-default port is legitimate for installs that expose Pano
 * directly (no reverse proxy) — those must round-trip unchanged.
 */
object WebsiteUrlUtil {

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val uri = parse(trimmed) ?: return trimmed.trimEnd('/')
        val scheme = uri.scheme?.lowercase() ?: return trimmed.trimEnd('/')
        val host = uri.host?.lowercase() ?: return trimmed.trimEnd('/')
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        val port = uri.port.takeIf { it != -1 && it != defaultPort }
        val path = uri.rawPath?.trimEnd('/') ?: ""
        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != null) {
                append(':')
                append(port)
            }
            append(path)
        }
    }

    fun explicitPort(raw: String): Int? = parse(raw.trim())?.port?.takeIf { it != -1 }

    fun host(raw: String): String? = parse(raw.trim())?.host?.takeIf { it.isNotBlank() }

    /**
     * Strips an optional `:port` while keeping IPv6 brackets intact (`[::1]:8080` -> `[::1]`).
     */
    fun stripPort(host: String): String {
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            return if (end >= 0) host.substring(0, end + 1) else host
        }
        val colon = host.indexOf(':')
        return if (colon >= 0) host.substring(0, colon) else host
    }

    private fun parse(value: String): URI? {
        if (value.isEmpty()) return null
        return try {
            URI(if (value.contains("://")) value else "https://$value")
        } catch (_: Exception) {
            null
        }
    }
}
