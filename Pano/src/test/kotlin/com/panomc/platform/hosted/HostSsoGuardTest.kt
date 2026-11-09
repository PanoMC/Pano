package com.panomc.platform.hosted

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostSsoGuardTest {
    /** Refill slow enough that nothing refills during a test. */
    private fun guard(maxInFlight: Int = 100, failureBurst: Int = 100, clock: () -> Long = System::currentTimeMillis) =
        HostSsoGuard(
            perIpBurst = 5, perIpRefillMs = 3_600_000,
            failureBurst = failureBurst, failureRefillMs = 3_600_000,
            maxInFlight = maxInFlight, clock = clock
        )

    private fun HostSsoGuard.take(ip: String) = tryAcquire(ip).also { if (it) release() }

    @Test
    fun `one ip gets a small burst, another ip is unaffected`() {
        val guard = guard()

        repeat(5) { assertTrue(guard.take("203.0.113.1")) }
        assertFalse(guard.take("203.0.113.1"))
        assertTrue(guard.take("203.0.113.2"))
    }

    @Test
    fun `refused tickets from many ips use up the shared budget and lock out only those ips`() {
        var now = 1_000_000L
        val guard = guard(failureBurst = 8, clock = { now })

        // 50 addresses each send a ticket the control plane refuses.
        (1..50).forEach { i ->
            val ip = "198.51.100.$i"
            if (guard.take(ip)) guard.recordFailure(ip)
        }

        // The budget is gone: an address that failed is refused before its next ticket is redeemed ...
        assertFalse(guard.take("198.51.100.1"))

        // ... but the owner, who never sent a bad ticket, still gets in.
        assertTrue(guard.take("203.0.113.77"))

        // A failure is forgotten after ten minutes.
        now += 10 * 60_000L
        assertTrue(guard.take("198.51.100.2"))
    }

    @Test
    fun `garbage tickets do not use the budget of good ones`() {
        val guard = guard(failureBurst = 1)

        // Plenty of accepted tickets never touch the failure budget.
        repeat(20) { assertTrue(guard.take("203.0.113.${it + 1}")) }

        guard.recordFailure("198.51.100.9")

        assertFalse(guard.take("198.51.100.9"))
        assertTrue(guard.take("203.0.113.200"))
    }

    @Test
    fun `an ipv6 network is one client`() {
        assertEquals(HostSsoGuard.clientKey("2001:db8:1:2::1"), HostSsoGuard.clientKey("2001:db8:1:2:ffff::9"))
        assertNotEquals(HostSsoGuard.clientKey("2001:db8:1:2::1"), HostSsoGuard.clientKey("2001:db8:1:3::1"))
        assertEquals("203.0.113.1", HostSsoGuard.clientKey("203.0.113.1"))
        assertEquals("unknown", HostSsoGuard.clientKey("unknown"))
    }

    @Test
    fun `redeems in flight are capped until released`() {
        val guard = guard(maxInFlight = 2)

        assertTrue(guard.tryAcquire("a"))
        assertTrue(guard.tryAcquire("b"))
        assertFalse(guard.tryAcquire("c"))

        guard.release()
        assertTrue(guard.tryAcquire("d"))
    }

    @Test
    fun `logs are throttled and report what was dropped`() {
        var now = 1_000_000L
        val guard = guard(clock = { now })
        val logged = mutableListOf<Int>()

        repeat(10) { guard.log { logged += it } }
        assertEquals(listOf(0), logged)

        now += 60_000
        guard.log { logged += it }
        assertEquals(listOf(0, 9), logged)
    }
}
