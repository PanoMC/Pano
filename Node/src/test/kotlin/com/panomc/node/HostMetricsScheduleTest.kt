package com.panomc.node

import com.panomc.node.server.MetricsSchedule
import com.panomc.node.server.MetricsSchedule.Decision.NONE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The host's own `NODE_METRICS` cadence (SM-65, §2.4.30): every ten seconds by default, as often as
 * a `SET_NODE_METRICS_INTERVAL` lease asks for while it lasts, and back to ten when it runs out.
 * The daemon asks the same [MetricsSchedule] a server uses, under [NodeDaemon.HOST_METRICS_KEY].
 */
class HostMetricsScheduleTest {
    private var now = 0L

    private val schedule = MetricsSchedule { now }

    /** How many reports go out over [millis], on the daemon's half-second tick. */
    private fun reports(millis: Long): Int = (0 until (millis / MetricsSchedule.TICK_MS).toInt()).count {
        (schedule.decide(NodeDaemon.HOST_METRICS_KEY, docker = false) != NONE).also { now += MetricsSchedule.TICK_MS }
    }

    @Test
    fun `nobody watching is one report every ten seconds`() {
        assertEquals(3, reports(30_000))
    }

    @Test
    fun `a one second lease is a report every second, and it runs out after ninety`() {
        schedule.setInterval(NodeDaemon.HOST_METRICS_KEY, 1_000)

        assertEquals(30, reports(30_000))

        // No renewal: the rest of the lease, then the default again.
        reports(MetricsSchedule.LEASE_MS)

        assertEquals(3, reports(30_000))
    }

    @Test
    fun `out of range requests are clamped to half a second and to the default`() {
        schedule.setInterval(NodeDaemon.HOST_METRICS_KEY, 10)

        assertEquals(20, reports(10_000))

        schedule.setInterval(NodeDaemon.HOST_METRICS_KEY, 60_000)

        assertEquals(1, reports(10_000))
    }

    @Test
    fun `the host key can never be a server uuid`() {
        assertEquals(false, com.panomc.node.util.PathSafety.isSafeSegment(NodeDaemon.HOST_METRICS_KEY) &&
            NodeDaemon.HOST_METRICS_KEY.matches(Regex("^[0-9a-fA-F-]{36}$")))
    }
}
