package com.panomc.platform.util

import io.vertx.core.http.HttpServerRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

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
 *
 * Every address is parsed strictly (see [parseIp]) before it is compared or believed, and nothing is
 * ever handed to `InetAddress.getByName`, so a forged hop can neither become a client address nor
 * cost a DNS lookup on the event loop.
 */
object TrustedProxyIpResolver {

    private val logger = LoggerFactory.getLogger(TrustedProxyIpResolver::class.java)

    /**
     * A `server.trusted-proxies` entry that stands for Cloudflare's published edge ranges, so an
     * operator behind Cloudflare writes one word instead of copying (and later refreshing) the list.
     * Never trusted unless listed: anyone with a Cloudflare account can send requests from these
     * addresses with any header they like.
     */
    const val CLOUDFLARE_KEYWORD = "cloudflare"

    /** https://www.cloudflare.com/ips-v4 and /ips-v6. Refresh when Cloudflare announces new ranges. */
    val CLOUDFLARE_RANGES = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
        "2a06:98c0::/29", "2c0f:f248::/32"
    )

    private val cloudflareCidrs: List<Cidr> by lazy { CLOUDFLARE_RANGES.mapNotNull { parseCidr(it) } }

    private const val PROXY_HINT_INTERVAL_MS = 10 * 60_000L

    private val lastProxyHintAt = AtomicLong(0L)

    private class Cidr(val network: ByteArray, val prefix: Int)

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

        val address = parseIp(ip)

        return trustedProxies.any { entry ->
            val normalized = normalizeIp(entry)

            when {
                normalized == null -> false
                normalized == CLOUDFLARE_KEYWORD -> address != null && cloudflareCidrs.any { it.contains(address) }
                normalized.contains('/') -> address != null && parseCidr(normalized)?.contains(address) == true
                else -> {
                    val listed = parseIp(normalized)

                    if (address != null && listed != null) address.contentEquals(listed) else normalized == ip
                }
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
    fun resolveClientIp(request: HttpServerRequest, trustedProxies: List<String>): String {
        val socketPeer = request.remoteAddress()?.host()
        val forwardedFor = request.getHeader("X-Forwarded-For")
        val resolved = resolveClientIp(socketPeer, forwardedFor, request.getHeader("X-Real-IP"), trustedProxies)

        if (needsProxyHint(normalizeIp(socketPeer), resolved, forwardedFor, request.getHeader("CF-Connecting-IP"))) {
            hintAboutUnlistedProxy(resolved)
        }

        return resolved
    }

    /**
     * Whether the resolved address looks like the edge of a proxy that is not listed in
     * `server.trusted-proxies`: the request names a different visitor in `CF-Connecting-IP`, or the
     * chosen `X-Forwarded-For` hop still has other hops to its left. Both can also be a visitor
     * forging headers, which is why the hint is only logged, throttled, and never acted on.
     */
    internal fun needsProxyHint(socketPeer: String?, resolved: String, forwardedFor: String?, cfConnectingIp: String?): Boolean {
        if (!cfConnectingIp.isNullOrBlank() && normalizeIp(cfConnectingIp) != resolved) {
            return true
        }

        if (forwardedFor.isNullOrBlank() || resolved == socketPeer || !forwardedFor.contains(',')) {
            return false
        }

        return normalizeIp(forwardedFor.substringBefore(',')) != resolved
    }

    private fun hintAboutUnlistedProxy(resolved: String) {
        val now = System.currentTimeMillis()
        val last = lastProxyHintAt.get()

        if (now - last < PROXY_HINT_INTERVAL_MS || !lastProxyHintAt.compareAndSet(last, now)) {
            return
        }

        logger.warn(
            "A request names another visitor than {} in CF-Connecting-IP or earlier X-Forwarded-For hops. " +
                    "If a proxy or CDN sits in front of Pano, add it to server.trusted-proxies " +
                    "(\"{}\" covers Cloudflare) so its address is skipped; otherwise ignore this, the headers were forged.",
            resolved,
            CLOUDFLARE_KEYWORD
        )
    }

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
                if (hop == null || parseIp(hop) == null) {
                    return socketPeer
                }

                if (!isTrustedProxy(hop, trustedProxies)) {
                    return hop
                }
            }
        }

        normalizeIp(realIp)?.takeIf { parseIp(it) != null }?.let { return it }

        return socketPeer
    }

    /**
     * The bytes of an IPv4 (4) or IPv6 (16) literal, or null when [raw] is not exactly one. Pure
     * string work: the JDK parser is not used because it falls back to a name lookup for text that
     * merely looks like an address. An optional IPv6 zone (`%eth0`) is accepted and dropped, an
     * IPv4-mapped IPv6 address (`::ffff:1.2.3.4`) comes back as its 4 IPv4 bytes.
     */
    internal fun parseIp(raw: String): ByteArray? {
        var text = raw.trim().removePrefix("[").removeSuffix("]").lowercase()

        if (!text.contains(':')) {
            return parseIpv4(text)
        }

        val zone = text.indexOf('%')

        if (zone >= 0) {
            if (zone == text.length - 1 || !text.substring(zone + 1).all { it.isLetterOrDigit() || it in "._-" }) {
                return null
            }

            text = text.substring(0, zone)
        }

        if (text.length > 45 || !text.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) {
            return null
        }

        if (text.contains('.')) {
            val split = text.lastIndexOf(':')
            val tail = parseIpv4(text.substring(split + 1)) ?: return null

            val high = ((tail[0].toInt() and 0xFF) shl 8) or (tail[1].toInt() and 0xFF)
            val low = ((tail[2].toInt() and 0xFF) shl 8) or (tail[3].toInt() and 0xFF)

            text = text.substring(0, split + 1) + Integer.toHexString(high) + ":" + Integer.toHexString(low)
        }

        val gap = text.indexOf("::")
        val head: String
        val rest: String?

        if (gap >= 0) {
            if (text.indexOf("::", gap + 1) >= 0) return null

            head = text.substring(0, gap)
            rest = text.substring(gap + 2)
        } else {
            head = text
            rest = null
        }

        val front = hexGroups(head) ?: return null
        val back = (if (rest == null) emptyList() else hexGroups(rest)) ?: return null
        val missing = 8 - front.size - back.size

        if (if (rest == null) missing != 0 else missing < 1) return null

        val groups = front + List(if (rest == null) 0 else missing) { 0 } + back
        val bytes = ByteArray(16)

        groups.forEachIndexed { i, group ->
            bytes[i * 2] = (group shr 8).toByte()
            bytes[i * 2 + 1] = group.toByte()
        }

        val mapped = (0 until 10).all { bytes[it].toInt() == 0 } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

        return if (mapped) bytes.copyOfRange(12, 16) else bytes
    }

    private fun hexGroups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()

        return part.split(':').map { group ->
            if (group.isEmpty() || group.length > 4) return null

            group.toInt(16)
        }
    }

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')

        if (parts.size != 4) return null

        val bytes = ByteArray(4)

        parts.forEachIndexed { i, part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null

            val value = part.toInt()

            if (value > 255) return null

            bytes[i] = value.toByte()
        }

        return bytes
    }

    private fun parseCidr(text: String): Cidr? {
        val network = parseIp(text.substringBefore('/')) ?: return null
        val prefix = text.substringAfter('/', "").takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } && it.length <= 3 }
            ?.toInt() ?: return null

        return if (prefix <= network.size * 8) Cidr(network, prefix) else null
    }

    private fun Cidr.contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false

        val fullBytes = prefix / 8
        val remainingBits = prefix % 8

        for (i in 0 until fullBytes) {
            if (address[i] != network[i]) return false
        }

        if (remainingBits == 0) return true

        val mask = (0xFF shl (8 - remainingBits)) and 0xFF

        return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    private fun rightmostUntrustedForwardedHop(
        request: HttpServerRequest,
        trustedProxies: List<String>
    ): String? {
        val header = request.getHeader("X-Forwarded-For") ?: return null
        val hops = header.split(",").mapNotNull { normalizeIp(it) }

        for (index in hops.indices.reversed()) {
            val hop = hops[index]

            // A hop that is not an address is never believed; the socket peer stands in.
            if (parseIp(hop) == null) {
                return null
            }

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
