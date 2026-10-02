package com.panomc.platform.update

import com.panomc.platform.config.PanoConfig
import com.panomc.platform.config.migration.ConfigMigration37To38
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.UpdatePeriod
import com.panomc.platform.util.UpdateSource
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class UpdateSourceConfigTest {
    @Test
    fun `update-source values parse leniently`() {
        assertEquals(UpdateSource.AUTO, UpdateSource.parse("AUTO"))
        assertEquals(UpdateSource.PANO_API, UpdateSource.parse("pano-api"))
        assertEquals(UpdateSource.PANO_API, UpdateSource.parse(" Pano_Api "))
        assertEquals(UpdateSource.GITHUB, UpdateSource.parse("github"))
        assertNull(UpdateSource.parse("gitlab"))
        assertNull(UpdateSource.parse(""))
        assertNull(UpdateSource.parse(null))
    }

    @Test
    fun `config reads update-source and defaults to AUTO`() {
        val base = JsonObject().put("config-version", 38)

        assertEquals(UpdateSource.AUTO, PanoConfig.from(base.copy().put("update-source", "AUTO")).effectiveUpdateSource)
        assertEquals(UpdateSource.GITHUB, PanoConfig.from(base.copy().put("update-source", "GITHUB")).effectiveUpdateSource)
        assertEquals(UpdateSource.PANO_API, PanoConfig.from(base.copy().put("update-source", "pano-api")).effectiveUpdateSource)
        assertEquals(UpdateSource.AUTO, PanoConfig.from(base.copy().put("update-source", "nonsense")).effectiveUpdateSource)
        assertEquals(UpdateSource.AUTO, PanoConfig.from(base).effectiveUpdateSource)
    }

    @Test
    fun `migration 37 to 38 adds update-source and keeps an existing one`() {
        val migration = ConfigMigration37To38()

        assertEquals(37, migration.from)
        assertEquals(38, migration.to)
        assertTrue(migration.isMigratable(37))
        assertFalse(migration.isMigratable(36))

        val config = JsonObject().put("config-version", 37)
        migration.migrate(config)
        assertEquals("AUTO", config.getString("update-source"))

        val kept = JsonObject().put("update-source", "GITHUB")
        migration.migrate(kept)
        assertEquals("GITHUB", kept.getString("update-source"))
    }

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `default jitter and startup delay stay within their bounds`() {
        repeat(500) {
            val schedule = UpdateCheckSchedule()

            assertTrue(schedule.jitterMs in 0 until UpdateCheckSchedule.MAX_JITTER_MS)
            assertTrue(schedule.startupDelayMs in 0 until UpdateCheckSchedule.MAX_STARTUP_DELAY_MS)
        }
    }

    @Test
    fun `a check is due once per period, jitter into the new one`() {
        val jitter = 90 * 60 * 1000L
        val schedule = UpdateCheckSchedule(jitterMs = jitter, startupDelayMs = 0, startedAt = 0, zone = zone)
        val last = at("2026-10-02T23:50:00")

        assertFalse(schedule.isDue(UpdatePeriod.ONCE_PER_DAY, last, at("2026-10-02T23:59:00")))
        assertFalse(schedule.isDue(UpdatePeriod.ONCE_PER_DAY, last, at("2026-10-03T01:29:59")))
        assertTrue(schedule.isDue(UpdatePeriod.ONCE_PER_DAY, last, at("2026-10-03T01:30:00")))

        // 2026-10-02 is a Friday: the next ISO week starts Monday 2026-10-05.
        assertFalse(schedule.isDue(UpdatePeriod.ONCE_PER_WEEK, last, at("2026-10-04T23:00:00")))
        assertTrue(schedule.isDue(UpdatePeriod.ONCE_PER_WEEK, last, at("2026-10-05T02:00:00")))

        assertFalse(schedule.isDue(UpdatePeriod.ONCE_PER_MONTH, last, at("2026-10-31T23:00:00")))
        assertTrue(schedule.isDue(UpdatePeriod.ONCE_PER_MONTH, last, at("2026-11-01T01:30:00")))

        // Across a year boundary (the old week-number comparison missed this one).
        val december = at("2026-12-30T10:00:00")
        assertTrue(schedule.isDue(UpdatePeriod.ONCE_PER_WEEK, december, at("2027-01-04T02:00:00")))
        assertTrue(schedule.isDue(UpdatePeriod.ONCE_PER_MONTH, december, at("2027-01-01T02:00:00")))

        assertFalse(schedule.isDue(UpdatePeriod.NEVER, last, at("2030-01-01T00:00:00")))
    }

    @Test
    fun `a Pano that never checked waits its startup delay`() {
        val schedule = UpdateCheckSchedule(jitterMs = 0, startupDelayMs = 60_000, startedAt = 1_000_000, zone = zone)

        assertFalse(schedule.isDue(UpdatePeriod.ONCE_PER_DAY, null, 1_000_000 + 59_999))
        assertTrue(schedule.isDue(UpdatePeriod.ONCE_PER_DAY, null, 1_000_000 + 60_000))
        assertFalse(schedule.isDue(UpdatePeriod.NEVER, null, Long.MAX_VALUE))
    }

    @Test
    fun `reads a sha256sum file`() {
        val hex = "daa3d3b09f098bbd443c2ccbe585cc02b2dd66b06fc45a8dc0dc775a6d62879e"

        assertEquals(hex, HashUtil.parseSha256File("$hex  Pano-1.0.0-alpha.529.jar\n"))
        assertEquals(hex, HashUtil.parseSha256File(hex.uppercase()))
        assertNull(HashUtil.parseSha256File("Not Found"))
        assertNull(HashUtil.parseSha256File(null))
    }
}
