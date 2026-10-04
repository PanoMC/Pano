package com.panomc.platform.server.metrics

import com.panomc.platform.util.TimeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * The ranges and bucket overrides of the metrics endpoints (§2.4.25).
 */
class MetricsSeriesWindowTest {
    private val now = System.currentTimeMillis()

    @Test
    fun `twelve hours is a range, in five-minute buckets`() {
        assertEquals(MetricsRange.HALF_DAY, MetricsRange.fromId("12h"))
        assertEquals(12L * 60L * 60L * 1000L, MetricsRange.HALF_DAY.durationMs)
        assertEquals(5L * 60L * 1000L, MetricsRange.HALF_DAY.bucketMs)

        // And the ones that were there are exactly as they were.
        assertEquals(listOf("1h", "12h", "24h", "7d", "30d"), MetricsRange.entries.map { it.id })
        assertEquals(60L * 1000L, MetricsRange.HOUR.bucketMs)
        assertEquals(60L * 60L * 1000L, MetricsRange.WEEK.bucketMs)
    }

    @Test
    fun `without an override a range keeps its own buckets`() {
        val window = MetricsSeriesWindow.of(MetricsRange.DAY, null)!!

        assertEquals(MetricsRange.DAY.bucketMs, window.bucketMs)
        assertFalse(window.localDays)
        assertEquals(0L, window.offsetMs(now))
        assertEquals(123_456L, window.key(123_456L), "keys untouched")
    }

    @Test
    fun `the three overrides are the only ones`() {
        assertEquals(MetricsBucket.MINUTE, MetricsBucket.fromId("minute"))
        assertEquals(MetricsBucket.HOUR, MetricsBucket.fromId("hour"))
        assertEquals(MetricsBucket.DAY, MetricsBucket.fromId("day"))
        assertNull(MetricsBucket.fromId("week"))
        assertNull(MetricsBucket.fromId("DAY"))
        assertNull(MetricsBucket.fromId(null))
    }

    @Test
    fun `the Performance week view is a week of days, cut at Pano's local midnight`() {
        val window = MetricsSeriesWindow.of(MetricsRange.WEEK, MetricsBucket.DAY)!!

        assertEquals(MetricsBucket.DAY.ms, window.bucketMs)
        assertTrue(window.localDays)
        assertEquals(TimeZone.getDefault().getOffset(now).toLong(), window.offsetMs(now))

        // A key the fixed offset put an hour off midnight still lands on that day's midnight.
        val midnight = TimeUtil.startOfDay(now)

        assertEquals(midnight, window.key(midnight))
        assertEquals(midnight, window.key(midnight - 60L * 60L * 1000L))
        assertEquals(midnight, window.key(midnight + 60L * 60L * 1000L))
    }

    @Test
    fun `an override that would draw too many points is refused`() {
        assertEquals(60L * 1000L, MetricsSeriesWindow.of(MetricsRange.DAY, MetricsBucket.MINUTE)?.bucketMs, "a day of minutes is the limit")
        assertEquals(60L * 60L * 1000L, MetricsSeriesWindow.of(MetricsRange.MONTH, MetricsBucket.HOUR)?.bucketMs)

        assertNull(MetricsSeriesWindow.of(MetricsRange.WEEK, MetricsBucket.MINUTE))
        assertNull(MetricsSeriesWindow.of(MetricsRange.MONTH, MetricsBucket.MINUTE))
    }
}
