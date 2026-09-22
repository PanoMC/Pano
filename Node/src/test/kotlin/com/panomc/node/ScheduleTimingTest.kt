package com.panomc.node

import com.panomc.node.schedule.CronSchedules
import com.panomc.node.schedule.ScheduleWarnings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The node's copy of the timing rules, checked against the same expectations as Pano's.
 *
 * Two implementations of one contract is the arrangement the daemon already uses for crypto and
 * paths; this is what stops the two from drifting.
 */
class ScheduleTimingTest {
    private fun at(zone: String, text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `agrees with Pano on what a valid cron is`() {
        assertTrue(CronSchedules.isValid("0 4 * * *"))
        assertFalse(CronSchedules.isValid("0 4 * *"))
        assertFalse(CronSchedules.isValid(null))
    }

    @Test
    fun `fires on the due minute regardless of where in it the tick lands`() {
        val zone = "Europe/Istanbul"

        assertTrue(CronSchedules.firesAt("30 3 * * *", zone, at(zone, "2026-06-01T03:30:00")))
        assertTrue(CronSchedules.firesAt("30 3 * * *", zone, at(zone, "2026-06-01T03:30:00") + 59_000))
        assertFalse(CronSchedules.firesAt("30 3 * * *", zone, at(zone, "2026-06-01T03:31:00")))
    }

    @Test
    fun `falls back to the local zone for an unknown one`() {
        assertEquals(ZoneId.systemDefault(), CronSchedules.zoneOf("Mars/Olympus"))
        assertEquals(ZoneId.of("UTC"), CronSchedules.zoneOf("UTC"))
    }

    @Test
    fun `warns on the same schedule Pano would`() {
        assertEquals(listOf(15, 5, 1), ScheduleWarnings.offsetsFor(15))
        assertEquals(listOf(5, 1), ScheduleWarnings.offsetsFor(5))
        assertTrue(ScheduleWarnings.offsetsFor(0).isEmpty())
        assertEquals("say Server restarts in 1 minute", ScheduleWarnings.message(1, restarting = true))
    }
}
