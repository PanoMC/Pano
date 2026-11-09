package com.panomc.platform.hosted

import com.panomc.platform.util.RateLimiter
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Throttles `GET /panel/host-sso` before it calls the control plane. The route is anonymous and
 * every accepted request becomes a server-to-server redeem carrying the instance secret; all
 * workloads on a Portal share one egress IP, so an unthrottled flood on one instance would spend
 * that IP's budget on the control plane for every tenant. Three limits apply:
 * per client, an instance-wide budget for tickets the control plane refused, and redeems in flight at once.
 *
 * The instance-wide budget is spent only by refused tickets, and once it is empty it only stops clients that
 * themselves sent a refused ticket lately ([recordFailure]). Garbage tickets from a few addresses therefore cannot
 * lock the owner's "Open panel" out: a client that never failed still gets through.
 *
 * A client is its IPv4 address or its IPv6 /64, so an attacker does not get a fresh client per address of one
 * IPv6 network.
 *
 * Real use is a person clicking "Open panel" on panomc.com, a few times a minute at most.
 */
class HostSsoGuard(
    perIpBurst: Int = 5,
    perIpRefillMs: Long = 12_000,
    failureBurst: Int = 20,
    failureRefillMs: Long = 6_000,
    private val failureMemoryMs: Long = 10 * 60_000L,
    private val maxInFlight: Int = 2,
    private val logIntervalMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val perIp = RateLimiter(perIpBurst, perIpRefillMs)
    private val failures = RateLimiter(failureBurst, failureRefillMs)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = AtomicInteger()
    private val lastLog = AtomicLong(Long.MIN_VALUE)
    private val suppressed = AtomicInteger()

    /** A slot for one redeem; must be [release]d. False when any limit is reached. */
    fun tryAcquire(clientIp: String): Boolean {
        val client = clientKey(clientIp)

        if (!perIp.tryAcquire(client)) return false

        if (failures.remainingTokens(INSTANCE) < 1 && failedRecently(client)) return false

        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet()
            return false
        }

        return true
    }

    fun release() {
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** The control plane refused the ticket [clientIp] sent: spends the shared failure budget and marks the client. */
    fun recordFailure(clientIp: String) {
        val now = clock()

        failures.tryAcquire(INSTANCE)

        if (failedAt.size > MAX_REMEMBERED) {
            failedAt.entries.removeIf { now - it.value >= failureMemoryMs }

            // Still full: start over, so a flood cannot grow this without bound.
            if (failedAt.size > MAX_REMEMBERED) failedAt.clear()
        }

        failedAt[clientKey(clientIp)] = now
    }

    private fun failedRecently(client: String): Boolean {
        val at = failedAt[client] ?: return false

        return clock() - at < failureMemoryMs
    }

    /**
     * Log throttle: runs [log] with the number of messages dropped since the last one at most once
     * per [logIntervalMs], so a flood of bad tickets cannot fill the instance log.
     */
    fun log(log: (suppressed: Int) -> Unit) {
        val now = clock()
        val last = lastLog.get()

        if (last != Long.MIN_VALUE && now - last < logIntervalMs || !lastLog.compareAndSet(last, now)) {
            suppressed.incrementAndGet()
            return
        }

        log(suppressed.getAndSet(0))
    }

    companion object {
        private const val INSTANCE = "instance"
        private const val MAX_REMEMBERED = 10_000

        /** The IPv4 address, or the /64 of an IPv6 address; anything that is not an IP literal as it is. */
        internal fun clientKey(clientIp: String): String {
            if (!clientIp.contains(':')) return clientIp

            val bytes = try {
                InetAddress.getByName(clientIp.removePrefix("[").removeSuffix("]")).address
            } catch (_: Exception) {
                return clientIp
            }

            if (bytes.size != 16) return clientIp

            return bytes.take(8).joinToString(":", postfix = "::/64") { "%02x".format(it) }
        }
    }
}
