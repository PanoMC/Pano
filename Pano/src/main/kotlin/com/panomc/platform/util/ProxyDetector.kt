package com.panomc.platform.util

import com.panomc.platform.route.WebsiteUrlRedirectHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Notices a reverse proxy that is set up wrongly, from live traffic only (in memory, nothing persisted).
 *
 * - [State.UNTRUSTED_PROXY]: a public peer that is not trusted sends many different `X-Forwarded-For`
 *   values, so it is a proxy whose forwarded address Pano ignores (add it to `server.trusted-proxies`).
 * - [State.SINGLE_CLIENT_IP]: nearly every request resolves to one private / loopback address, so the
 *   proxy in front sends no forwarding header and every visitor looks like the same client.
 *
 * Each peer is kept for [WINDOW_MS]; at most [MAX_PEERS] peers are tracked (a new peer is ignored while full).
 */
@Component
class ProxyDetector {
    enum class State { OK, UNTRUSTED_PROXY, SINGLE_CLIENT_IP }

    data class ProxyStatus(val state: String, val peers: List<String>, val suggestion: String?)

    companion object {
        const val WINDOW_MS = 10 * 60 * 1000L
        const val MAX_PEERS = 256
        const val UNTRUSTED_MIN_REQUESTS = 20
        const val UNTRUSTED_MIN_DISTINCT = 5
        const val SINGLE_MIN_REQUESTS = 100
        const val SINGLE_SHARE = 0.9

        private const val MAX_DISTINCT_TRACKED = 16
        private const val MAX_IPS_PER_PEER = 32
        private const val MAX_FIELD_LENGTH = 256
    }

    private class PeerRecord(var windowStart: Long) {
        var requests = 0
        var trustedPeer = false
        val forwardedValues = HashSet<String>()
        val ipCounts = HashMap<String, Int>()
        var other = 0
        var loopbackSkipped = 0
    }

    private val log = LoggerFactory.getLogger(ProxyDetector::class.java)
    private val lock = Any()
    private val peers = LinkedHashMap<String, PeerRecord>()
    private var warnedUntrusted = false
    private var warnedSingle = false

    /** Swappable for tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    fun observe(
        socketPeer: String?,
        resolvedIp: String,
        forwardedFor: String?,
        hasForwardingHeader: Boolean,
        trustedPeer: Boolean
    ) {
        val peer = TrustedProxyIpResolver.normalizeIp(socketPeer) ?: return
        val now = clock()

        val directLoopback = !hasForwardingHeader && WebsiteUrlRedirectHandler.isLoopbackIp(peer.removePrefix("::ffff:"))

        // The operator on the box says nothing about the proxy setup.
        if (directLoopback) return

        synchronized(lock) {
            evictExpired(now)

            var record = peers[peer]

            if (record == null) {
                if (peers.size >= MAX_PEERS) return

                record = PeerRecord(now)
                peers[peer] = record
            }

            record.requests++
            record.trustedPeer = trustedPeer

            val xff = forwardedFor?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_FIELD_LENGTH)

            if (xff != null && record.forwardedValues.size < MAX_DISTINCT_TRACKED) {
                record.forwardedValues.add(xff)
            }

            val ip = resolvedIp.take(MAX_FIELD_LENGTH)

            if (record.ipCounts.containsKey(ip) || record.ipCounts.size < MAX_IPS_PER_PEER) {
                record.ipCounts.merge(ip, 1, Int::plus)
            } else {
                record.other++
            }

            evaluate()
        }
    }

    fun status(): ProxyStatus {
        synchronized(lock) {
            evictExpired(clock())

            val untrusted = untrustedPeers()

            if (untrusted.isNotEmpty()) {
                return ProxyStatus(State.UNTRUSTED_PROXY.name, untrusted, trustedProxiesLine(untrusted))
            }

            val single = singleClientIp()

            if (single != null) {
                return ProxyStatus(State.SINGLE_CLIENT_IP.name, single.second, null)
            }

            return ProxyStatus("OK", emptyList(), null)
        }
    }

    private fun evictExpired(now: Long) {
        peers.entries.removeIf { now - it.value.windowStart >= WINDOW_MS }
    }

    private fun untrustedPeers(): List<String> =
        peers.filter { (_, r) ->
            !r.trustedPeer && r.requests >= UNTRUSTED_MIN_REQUESTS && r.forwardedValues.size >= UNTRUSTED_MIN_DISTINCT
        }.keys.toList()

    /** The shared address and the peers that sent it, when one private / loopback address makes up the traffic. */
    private fun singleClientIp(): Pair<String, List<String>>? {
        val total = peers.values.sumOf { it.requests }

        if (total < SINGLE_MIN_REQUESTS) return null

        val perIp = HashMap<String, Int>()

        peers.values.forEach { r -> r.ipCounts.forEach { (ip, n) -> perIp.merge(ip, n, Int::plus) } }

        val top = perIp.maxByOrNull { it.value } ?: return null

        if (top.value < total * SINGLE_SHARE) return null
        if (!TrustedProxyIpResolver.isPrivateOrLoopback(top.key)) return null

        val via = peers.filter { it.value.ipCounts.containsKey(top.key) }.keys.toList()

        return top.key to via
    }

    private fun trustedProxiesLine(peerList: List<String>): String =
        "trusted-proxies = [${peerList.joinToString(", ") { "\"$it\"" }}]"

    private fun evaluate() {
        if (!warnedUntrusted) {
            val untrusted = untrustedPeers()

            if (untrusted.isNotEmpty()) {
                warnedUntrusted = true

                log.warn(
                    "The peer {} sends many different X-Forwarded-For values but is not a trusted proxy, so every visitor " +
                        "is counted as that peer. If it is your reverse proxy, set in config.conf: {}",
                    untrusted.joinToString(", "),
                    trustedProxiesLine(untrusted)
                )
            }
        }

        if (!warnedSingle) {
            val single = singleClientIp()

            if (single != null) {
                warnedSingle = true

                log.warn(
                    "All visitors appear as {}: the proxy at {} sends no X-Forwarded-For.",
                    single.first,
                    single.second.joinToString(", ")
                )
            }
        }
    }
}
