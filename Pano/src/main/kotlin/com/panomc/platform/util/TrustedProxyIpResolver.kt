package com.panomc.platform.util

import io.vertx.core.http.HttpServerRequest

/**
 * The single derivation of "which address is this request really from" for everything that counts,
 * bans or rate limits maintenance-mode traffic.
 *
 * The socket peer always wins, because it is the only thing a client cannot forge. A forwarded
 * header is honoured only when the peer is listed in `server.trusted-proxies`, and then only its
 * rightmost untrusted hop — `.first()` is attacker-controlled.
 *
 * [com.panomc.platform.auth.AuthProvider.getRemoteIP] and [RateLimitManager.getClientIp] keep their
 * looser semantics on purpose (six other call sites); this stricter derivation is maintenance-mode
 * only, and both of its consumers share this implementation so they can never drift apart.
 */
object TrustedProxyIpResolver {

    /** Anything that claims a request was made on someone else's behalf. */
    private val FORWARDING_HEADERS = listOf("X-Forwarded-For", "X-Real-IP", "Forwarded")

    /**
     * [ip] is what gets counted, banned or bucketed; [socketPeer] is the TCP peer it was derived
     * from and is worth persisting so an operator can spot a spoofing proxy.
     *
     * [fromForwardedHeader] is false when [ip] *is* the socket peer — the only case in which a
     * loopback exemption may be applied, since a header-derived value is forgeable.
     */
    data class ResolvedIp(
        val ip: String,
        val socketPeer: String,
        val fromForwardedHeader: Boolean
    )

    /** Returns null only when the request has no usable socket peer at all. */
    fun resolve(request: HttpServerRequest, trustedProxies: List<String>): ResolvedIp? {
        val socketPeer = normalizeIp(request.remoteAddress()?.host()) ?: return null

        if (isTrustedProxy(socketPeer, trustedProxies)) {
            rightmostUntrustedForwardedHop(request, trustedProxies)?.let {
                return ResolvedIp(it, socketPeer, fromForwardedHeader = true)
            }
        }

        return ResolvedIp(socketPeer, socketPeer, fromForwardedHeader = false)
    }

    /**
     * True when something in front of us claims to be forwarding. A loopback socket peer means
     * "the operator is on the box" only while this is false — otherwise it is an unconfigured
     * same-host reverse proxy and exempting it would exempt the entire internet.
     */
    fun hasForwardingHeader(request: HttpServerRequest): Boolean =
        FORWARDING_HEADERS.any { !request.getHeader(it).isNullOrBlank() }

    fun isTrustedProxy(ip: String, trustedProxies: List<String>): Boolean {
        if (trustedProxies.isEmpty()) {
            return false
        }

        return trustedProxies.any { normalizeIp(it)?.equals(ip, ignoreCase = true) == true }
    }

    private fun rightmostUntrustedForwardedHop(
        request: HttpServerRequest,
        trustedProxies: List<String>
    ): String? {
        val header = request.getHeader("X-Forwarded-For") ?: return null
        val hops = header.split(",").mapNotNull { normalizeIp(it) }

        for (index in hops.indices.reversed()) {
            val hop = hops[index]

            if (!isTrustedProxy(hop, trustedProxies)) {
                return hop
            }
        }

        return null
    }

    fun normalizeIp(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }

        var ip = raw.trim().trim('"')

        if (ip.startsWith("[")) {
            val end = ip.indexOf(']')

            ip = if (end > 0) ip.substring(1, end) else ip.removePrefix("[")
        } else if (ip.count { it == ':' } == 1) {
            // "1.2.3.4:5678" — a port, not an IPv6 address.
            ip = ip.substringBefore(':')
        }

        return ip.lowercase().ifBlank { null }
    }
}
