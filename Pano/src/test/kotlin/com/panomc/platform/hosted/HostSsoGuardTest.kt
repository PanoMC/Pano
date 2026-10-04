package com.panomc.platform.hosted

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostSsoGuardTest {
    /** Refill slow enough that nothing refills during a test. */
    private fun guard(maxInFlight: Int = 100, globalBurst: Int = 100, clock: () -> Long = System::currentTimeMillis) =
        HostSsoGuard(
            perIpBurst = 5, perIpRefillMs = 3_600_000,
            globalBurst = globalBurst, globalRefillMs = 3_600_000,
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
    fun `the instance-wide cap holds against many ips`() {
        val guard = guard(globalBurst = 8)

        val allowed = (1..50).count { guard.take("198.51.100.$it") }

        assertEquals(8, allowed)
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
