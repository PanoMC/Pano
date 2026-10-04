package com.panomc.platform.server.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CronSchedulesTest {
    private fun at(zone: String, text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun wallClock(zone: String, epochMillis: Long): String =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.of(zone))
            .toLocalDateTime()
            .toString()

    @Test
    fun `accepts five-field unix cron and refuses the rest`() {
        assertTrue(CronSchedules.isValid("0 4 * * *"))
        assertTrue(CronSchedules.isValid("*/15 * * * *"))
        assertTrue(CronSchedules.isValid("0 4 * * 0"))

        assertFalse(CronSchedules.isValid("0 4 * *"))
        assertFalse(CronSchedules.isValid("not a cron"))
        assertFalse(CronSchedules.isValid(""))
        assertFalse(CronSchedules.isValid(null))
        // Six fields is Quartz, not Unix, and accepting it would schedule the wrong minute.
        assertFalse(CronSchedules.isValid("0 0 4 * * *"))
    }

    @Test
    fun `refuses an expression longer than the limit`() {
        val many = (0 until 60).joinToString(",")

        assertTrue(many.length > CronSchedules.MAX_CRON_LENGTH)
        assertFalse(CronSchedules.isValid("$many 4 * * *"))
    }

    @Test
    fun `validates time zones`() {
        assertTrue(CronSchedules.isValidZone("Europe/Istanbul"))
        assertTrue(CronSchedules.isValidZone("UTC"))
        assertFalse(CronSchedules.isValidZone("Mars/Olympus"))
        assertEquals(ZoneId.systemDefault(), CronSchedules.zoneOf(null))
    }

    @Test
    fun `previews the next five runs`() {
        val from = at("UTC", "2026-03-01T00:30:00")

        val next = CronSchedules.nextRuns("0 4 * * *", "UTC", 5, from)

        assertEquals(5, next.size)
        assertEquals("2026-03-01T04:00", wallClock("UTC", next.first()))
        assertEquals("2026-03-05T04:00", wallClock("UTC", next.last()))
        assertTrue(next.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `keeps the wall-clock hour across a spring-forward boundary`() {
        // Europe/Istanbul has no DST any more, so a zone that still does is what makes this a
        // real test: America/New_York springs forward on 2026-03-08.
        val zone = "America/New_York"
        val from = at(zone, "2026-03-06T12:00:00")

        val next = CronSchedules.nextRuns("0 4 * * *", zone, 4, from)

        val hours = next.map { wallClock(zone, it).substringAfter('T') }

        // Every run is still at four in the morning locally, even though one of the gaps between
        // them is 23 hours of real time.
        assertTrue(hours.all { it == "04:00" }, "expected every run at 04:00 local, got $hours")

        val gaps = next.zipWithNext().map { (a, b) -> b - a }

        assertTrue(gaps.contains(23L * 60 * 60 * 1000), "expected one 23-hour gap, got $gaps")
    }

    @Test
    fun `keeps the wall-clock hour across a fall-back boundary`() {
        val zone = "America/New_York"
        val from = at(zone, "2026-10-30T12:00:00")

        val next = CronSchedules.nextRuns("0 4 * * *", zone, 4, from)

        val hours = next.map { wallClock(zone, it).substringAfter('T') }

        assertTrue(hours.all { it == "04:00" }, "expected every run at 04:00 local, got $hours")

        val gaps = next.zipWithNext().map { (a, b) -> b - a }

        assertTrue(gaps.contains(25L * 60 * 60 * 1000), "expected one 25-hour gap, got $gaps")
    }

    @Test
    fun `fires on the minute it is due and not on the ones around it`() {
        val zone = "UTC"

        assertTrue(CronSchedules.firesAt("0 4 * * *", zone, at(zone, "2026-03-01T04:00:00")))
        // The tick never lands exactly on the second, which is the whole reason this works on
        // minutes rather than instants.
        assertTrue(CronSchedules.firesAt("0 4 * * *", zone, at(zone, "2026-03-01T04:00:00") + 12_345))
        assertFalse(CronSchedules.firesAt("0 4 * * *", zone, at(zone, "2026-03-01T03:59:00")))
        assertFalse(CronSchedules.firesAt("0 4 * * *", zone, at(zone, "2026-03-01T04:01:00")))
    }

    @Test
    fun `an unparsable expression never fires and has no next run`() {
        assertFalse(CronSchedules.firesAt("nonsense", "UTC", System.currentTimeMillis()))
        assertTrue(CronSchedules.nextRuns("nonsense", "UTC", 5).isEmpty())
        assertNotNull(CronSchedules.nextRun("0 4 * * *", "UTC"))
    }

    @Test
    fun `describes only what it can parse`() {
        assertNotNull(CronSchedules.describe("0 4 * * *"))
        assertEquals(null, CronSchedules.describe("nope"))
    }

    @Test
    fun `describes an expression in plain English rather than echoing it`() {
        val description = CronSchedules.describe("0 4 * * 0")

        assertNotNull(description)
        // The whole point of the field: it must not be the expression read back.
        assertFalse(description!!.contains("*"))
        assertTrue(description.contains("04:00"))
        assertTrue(description.lowercase().contains("sunday"))
    }
}
