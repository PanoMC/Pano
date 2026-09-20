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

    /**
     * True when [host] can only name the machine or network Pano runs on: `localhost` and
     * `*.localhost`, mDNS `*.local`, loopback, RFC 1918 / CGNAT (100.64/10, e.g. Tailscale) /
     * link-local IPv4, or IPv6 loopback / link-local / unique-local. Such a website-url is never a
     * canonical public host. Pure string parsing — no DNS lookups.
     */
    fun isNonPublicHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "0.0.0.0") return true
        if (h.contains(':')) return isNonPublicIpv6(h)
        return parseIpv4(h)?.let { isNonPublicIpv4(it) } ?: false
    }

    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return h.contains(':') || parseIpv4(h) != null
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        parts.forEachIndexed { i, part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isNonPublicIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
    }

    private fun isNonPublicIpv6(h: String): Boolean {
        if (h == "::1" || h == "::" || h.startsWith("0:0:0:0:0:0:0:1")) return true
        if (h.startsWith("::ffff:")) {
            return parseIpv4(h.removePrefix("::ffff:"))?.let { isNonPublicIpv4(it) } ?: false
        }
        val first = h.substringBefore(':')
        // fe80::/10 link-local, fc00::/7 unique local
        if (first.length == 4 && first.startsWith("fe") && first[2] in "89ab") return true
        return first.startsWith("fc") || first.startsWith("fd")
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
