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
 * [resolve] is the strict derivation maintenance mode uses: only peers listed in
 * `server.trusted-proxies` count as proxies. [resolveClientIp] is the one derivation for every other
 * caller (login IP, sessions, visitor counting, rate limiting, the UI proxy): the same rule, except
 * that a loopback or private-network peer (a local nginx, a Docker bridge) is trusted without
 * configuration. Both honour only the rightmost untrusted `X-Forwarded-For` hop, never `.first()`.
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

        return trustedProxies.any { entry ->
            val normalized = normalizeIp(entry)

            if (normalized != null && normalized.contains('/')) {
                matchesCidr(ip, normalized)
            } else {
                normalized?.equals(ip, ignoreCase = true) == true
            }
        }
    }

    /**
     * Loopback, RFC 1918, carrier-grade NAT (100.64/10), link-local and IPv6 unique-local peers.
     * A peer like that is a reverse proxy on the same host or network, never a browser on the open
     * internet, so it may declare the client address without being listed.
     */
    fun isPrivateOrLoopback(ip: String): Boolean {
        val normalized = normalizeIp(ip) ?: return false
        val mapped = normalized.removePrefix("::ffff:")

        return normalized == "localhost" || WebsiteUrlUtil.isNonPublicHost(mapped) && WebsiteUrlUtil.isIpLiteral(mapped)
    }

    /** A peer whose forwarded-client-address headers are believed. */
    fun isTrustedPeer(socketPeer: String, trustedProxies: List<String>): Boolean =
        isTrustedProxy(socketPeer, trustedProxies) || isPrivateOrLoopback(socketPeer)

    /**
     * THE client address, used everywhere an address is stored, counted or limited.
     *
     * The socket peer is the answer unless it is a trusted peer ([isTrustedPeer]); then the rightmost
     * `X-Forwarded-For` hop that is not itself a configured proxy wins (every proxy appends the
     * address it saw, so the leftmost values are whatever the client wrote), then `X-Real-IP`. A
     * hop that is not an IP literal is never believed and drops back to the socket peer. Returns
     * `"unknown"` only when the request has no socket peer at all.
     */
    fun resolveClientIp(request: HttpServerRequest, trustedProxies: List<String>): String =
        resolveClientIp(
            request.remoteAddress()?.host(),
            request.getHeader("X-Forwarded-For"),
            request.getHeader("X-Real-IP"),
            trustedProxies
        )

    fun resolveClientIp(
        socketPeerRaw: String?,
        forwardedFor: String?,
        realIp: String?,
        trustedProxies: List<String>
    ): String {
        val socketPeer = normalizeIp(socketPeerRaw) ?: return "unknown"

        if (!isTrustedPeer(socketPeer, trustedProxies)) {
            return socketPeer
        }

        if (!forwardedFor.isNullOrBlank()) {
            val hops = forwardedFor.split(",").map { normalizeIp(it) }

            for (hop in hops.reversed()) {
                if (hop == null || !WebsiteUrlUtil.isIpLiteral(hop)) {
                    return socketPeer
                }

                if (!isTrustedProxy(hop, trustedProxies)) {
                    return hop
                }
            }
        }

        normalizeIp(realIp)?.takeIf { WebsiteUrlUtil.isIpLiteral(it) }?.let { return it }

        return socketPeer
    }

    private fun matchesCidr(ip: String, cidr: String): Boolean {
        return try {
            val network = cidr.substringBefore('/')
            val prefix = cidr.substringAfter('/').toInt()

            if (!WebsiteUrlUtil.isIpLiteral(ip) || !WebsiteUrlUtil.isIpLiteral(network)) {
                return false
            }

            val address = java.net.InetAddress.getByName(ip).address
            val base = java.net.InetAddress.getByName(network).address

            if (address.size != base.size || prefix < 0 || prefix > base.size * 8) {
                return false
            }

            val fullBytes = prefix / 8
            val remainingBits = prefix % 8

            for (i in 0 until fullBytes) {
                if (address[i] != base[i]) return false
            }

            if (remainingBits == 0) {
                true
            } else {
                val mask = (0xFF shl (8 - remainingBits)) and 0xFF

                (address[fullBytes].toInt() and mask) == (base[fullBytes].toInt() and mask)
            }
        } catch (_: Exception) {
            false
        }
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
