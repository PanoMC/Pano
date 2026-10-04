package com.panomc.platform.hosted

import com.panomc.platform.util.RateLimiter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Throttles `GET /panel/host-sso` before it calls the control plane. The route is anonymous and
 * every accepted request becomes a server-to-server redeem carrying the instance secret; all
 * workloads on a Portal share one egress IP, so an unthrottled flood on one instance would spend
 * that IP's budget on the control plane for every tenant. Three limits apply:
 * per client IP, per instance (whatever the IP) and redeems in flight at once.
 *
 * Real use is a person clicking "Open panel" on panomc.com, a few times a minute at most.
 */
class HostSsoGuard(
    perIpBurst: Int = 5,
    perIpRefillMs: Long = 12_000,
    globalBurst: Int = 20,
    globalRefillMs: Long = 6_000,
    private val maxInFlight: Int = 2,
    private val logIntervalMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val perIp = RateLimiter(perIpBurst, perIpRefillMs)
    private val global = RateLimiter(globalBurst, globalRefillMs)
    private val inFlight = AtomicInteger()
    private val lastLog = AtomicLong(Long.MIN_VALUE)
    private val suppressed = AtomicInteger()

    /** A slot for one redeem; must be [release]d. False when any limit is reached. */
    fun tryAcquire(clientIp: String): Boolean {
        if (!perIp.tryAcquire(clientIp)) return false
        if (!global.tryAcquire("instance")) return false

        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet()
            return false
        }

        return true
    }

    fun release() {
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
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
}
