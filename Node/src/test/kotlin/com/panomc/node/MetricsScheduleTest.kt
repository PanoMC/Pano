package com.panomc.node

import com.panomc.node.server.MetricsSchedule
import com.panomc.node.server.MetricsSchedule.Decision.FULL
import com.panomc.node.server.MetricsSchedule.Decision.NONE
import com.panomc.node.server.MetricsSchedule.Decision.PROCESS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The per-server vitals cadence (§2.4.23 A): a full report every ten seconds whatever happens, and
 * process samples in between only while a lease from Pano says so.
 */
class MetricsScheduleTest {
    private companion object {
        val TICKS_PER_FULL = (MetricsSchedule.DEFAULT_INTERVAL_MS / MetricsSchedule.TICK_MS).toInt()
    }

    private var now = 0L

    private val schedule = MetricsSchedule { now }

    /** The decisions for [uuid] on the next [ticks] half-second ticks. */
    private fun run(uuid: String, ticks: Int, docker: Boolean = false): List<MetricsSchedule.Decision> =
        (0 until ticks).map {
            schedule.decide(uuid, docker).also { now += MetricsSchedule.TICK_MS }
        }

    /** One full report followed by [between] ticks alternating [pattern], ten seconds in all. */
    private fun cycle(vararg pattern: MetricsSchedule.Decision): List<MetricsSchedule.Decision> =
        listOf(FULL) + (1 until TICKS_PER_FULL).map { pattern[(it - 1) % pattern.size] }

    @Test
    fun `the tick is the fastest rate, and ten seconds is twenty of them`() {
        assertEquals(500L, MetricsSchedule.TICK_MS)
        assertEquals(500L, MetricsSchedule.MIN_INTERVAL_MS)
        assertEquals(20, TICKS_PER_FULL)
    }

    @Test
    fun `with nobody watching it is exactly the old ten-second report`() {
        assertEquals(cycle(NONE) + cycle(NONE), run("a", 2 * TICKS_PER_FULL))
    }

    @Test
    fun `a half-second watcher gets a process sample on every tick`() {
        schedule.setInterval("a", 500)

        assertEquals(cycle(PROCESS) + listOf(FULL), run("a", TICKS_PER_FULL + 1))
    }

    @Test
    fun `a one-second watcher gets a process sample every other tick between two full reports`() {
        schedule.setInterval("a", 1_000)

        assertEquals(cycle(NONE, PROCESS) + listOf(FULL), run("a", TICKS_PER_FULL + 1))
    }

    @Test
    fun `a slower watcher lands on whole ticks, not the tick after`() {
        schedule.setInterval("a", 2_000)

        assertEquals(cycle(NONE, NONE, NONE, PROCESS) + listOf(FULL), run("a", TICKS_PER_FULL + 1))
    }

    @Test
    fun `a container is never sampled faster than five seconds`() {
        schedule.setInterval("a", 500)

        assertEquals(5_000L, schedule.intervalOf("a", docker = true))

        // Five seconds is ten ticks: one process sample halfway between two full reports.
        val expected = listOf(FULL) + List(9) { NONE } + listOf(PROCESS) + List(9) { NONE } + listOf(FULL)

        assertEquals(expected, run("a", TICKS_PER_FULL + 1, docker = true))
    }

    @Test
    fun `the rate lapses ninety seconds after it was last asked for`() {
        schedule.setInterval("a", 1_000)

        now += MetricsSchedule.LEASE_MS - 1

        assertEquals(1_000L, schedule.intervalOf("a", docker = false))

        now += 1

        assertEquals(MetricsSchedule.DEFAULT_INTERVAL_MS, schedule.intervalOf("a", docker = false))
    }

    @Test
    fun `asking again renews the lease`() {
        schedule.setInterval("a", 1_000)

        now += 60_000

        schedule.setInterval("a", 1_000)

        now += 60_000

        assertEquals(1_000L, schedule.intervalOf("a", docker = false))
    }

    @Test
    fun `a request is clamped between half a second and the default`() {
        schedule.setInterval("a", 200)
        assertEquals(500L, schedule.intervalOf("a", docker = false))

        // Slower than the default is the default: the full report comes every ten seconds anyway.
        schedule.setInterval("a", 30_000)
        assertEquals(MetricsSchedule.DEFAULT_INTERVAL_MS, schedule.intervalOf("a", docker = false))

        schedule.setInterval("b", null)
        assertEquals(MetricsSchedule.DEFAULT_INTERVAL_MS, schedule.intervalOf("b", docker = false))
    }

    @Test
    fun `back to the default the moment Pano says so`() {
        schedule.setInterval("a", 1_000)
        schedule.setInterval("a", 10_000)

        assertEquals(cycle(NONE), run("a", TICKS_PER_FULL))
    }

    @Test
    fun `servers keep their own cadence`() {
        schedule.setInterval("fast", 500)

        val fast = mutableListOf<MetricsSchedule.Decision>()
        val slow = mutableListOf<MetricsSchedule.Decision>()

        repeat(3) {
            fast.add(schedule.decide("fast", docker = false))
            slow.add(schedule.decide("slow", docker = false))

            now += MetricsSchedule.TICK_MS
        }

        assertEquals(listOf(FULL, PROCESS, PROCESS), fast)
        assertEquals(listOf(FULL, NONE, NONE), slow)
    }

    @Test
    fun `a forgotten server starts over`() {
        schedule.setInterval("a", 1_000)

        run("a", 3)

        schedule.forget("a")

        assertEquals(MetricsSchedule.DEFAULT_INTERVAL_MS, schedule.intervalOf("a", docker = false))
        assertEquals(FULL, schedule.decide("a", docker = false))
    }
}
