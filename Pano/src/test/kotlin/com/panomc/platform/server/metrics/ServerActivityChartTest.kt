package com.panomc.platform.server.metrics

import com.panomc.platform.server.dto.PlayerActivity
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TimeUtil
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * The buckets of the Server Activity chart and what lands on them (§2.4.19).
 *
 * Two things are being pinned here. The buckets are the statistics page's buckets — local
 * midnights, today plus seven or thirty — because the two charts are read as the same axis. And
 * every one of them is present in the answer: a map with holes draws a line through days that
 * never happened.
 */
class ServerActivityChartTest {
    private val now = System.currentTimeMillis()

    @Test
    fun `a week is seven days and today, a month thirty and today`() {
        assertEquals(7, ServerActivityChart.amountOfDays(ServerActivityPeriod.WEEK))
        assertEquals(30, ServerActivityChart.amountOfDays(ServerActivityPeriod.MONTH))

        assertEquals(8, ServerActivityChart.dayBuckets(now, 7).size)
        assertEquals(31, ServerActivityChart.dayBuckets(now, 30).size)
    }

    @Test
    fun `the buckets are local midnights, oldest first, ending today`() {
        val buckets = ServerActivityChart.dayBuckets(now, 7)

        assertEquals(buckets.sorted(), buckets, "oldest first")
        assertEquals(TimeUtil.startOfDay(now), buckets.last(), "today is the last bucket")

        buckets.forEach { bucket ->
            assertEquals(bucket, TimeUtil.startOfDay(bucket), "every bucket is a local midnight")
        }

        // Consecutive days, whatever the clocks did in between.
        buckets.zipWithNext().forEach { (earlier, later) ->
            val calendar = Calendar.getInstance()

            calendar.timeInMillis = earlier
            calendar.add(Calendar.DAY_OF_YEAR, 1)

            assertEquals(calendar.timeInMillis, later)
        }
    }

    @Test
    fun `a day the database never heard of is a zero and not a gap`() {
        val buckets = ServerActivityChart.dayBuckets(now, 7)
        val today = buckets.last()

        val peaks = ServerActivityChart.peaks(buckets, mapOf(today to PlayerActivity(12, 4.5)))
        val averages = ServerActivityChart.averages(buckets, mapOf(today to PlayerActivity(12, 4.5)))

        assertEquals(buckets.size, peaks.size)
        assertEquals(buckets.size, averages.size)
        assertEquals(12L, peaks[today])
        assertEquals(4.5, averages[today])

        buckets.dropLast(1).forEach { bucket ->
            assertEquals(0L, peaks[bucket])
            assertEquals(0.0, averages[bucket])
        }
    }

    @Test
    fun `averages are rounded to the one decimal the tooltip shows`() {
        assertEquals(4.5, ServerActivityChart.round(4.499999999))
        assertEquals(4.6, ServerActivityChart.round(4.55))
        assertEquals(0.0, ServerActivityChart.round(0.0))
        assertEquals(12.3, ServerActivityChart.round(12.34567))

        val buckets = ServerActivityChart.dayBuckets(now, 7)

        assertEquals(
            3.3,
            ServerActivityChart.averages(buckets, mapOf(buckets.last() to PlayerActivity(9, 10.0 / 3.0)))[buckets.last()]
        )
    }

    @Test
    fun `a day the clocks moved on still lands on its own bucket`() {
        val buckets = ServerActivityChart.dayBuckets(now, 7)
        val target = buckets[3]

        // What a fixed-offset grouping returns for a 23 or 25 hour day: the right day, an hour
        // either side of its midnight.
        val shiftedForward = target + 60L * 60L * 1000L
        val shiftedBack = target - 60L * 60L * 1000L

        assertEquals(7L, ServerActivityChart.peaks(buckets, mapOf(shiftedForward to PlayerActivity(7, 1.0)))[target])
        assertEquals(9L, ServerActivityChart.peaks(buckets, mapOf(shiftedBack to PlayerActivity(9, 1.0)))[target])
    }

    @Test
    fun `the maps go out keyed by the midnight millisecond, as the statistics maps do`() {
        val buckets = ServerActivityChart.dayBuckets(now, 7)
        val today = buckets.last()

        val json = JsonObject(
            mapOf<String, Any?>(
                "peakPlayerData" to ServerActivityChart.peaks(buckets, mapOf(today to PlayerActivity(12, 4.5)))
            )
        ).encode()

        // A JSON object has string keys, so the number becomes its own decimal spelling — which is
        // what the panel already parses on the statistics page.
        assertTrue(json.contains("\"$today\":12"), json)
    }

    @Test
    fun `the chart's periods are its own, and the Statistics page's are left as they were`() {
        assertEquals(ServerActivityPeriod.YEAR, ServerActivityPeriod.fromName("YEAR"))
        assertEquals(ServerActivityPeriod.MONTH, ServerActivityPeriod.fromName("MONTH"))
        assertEquals(ServerActivityPeriod.WEEK, ServerActivityPeriod.fromName(null))
        assertEquals(ServerActivityPeriod.WEEK, ServerActivityPeriod.fromName("year"))

        // The Statistics API enumerates this one into its request validation: a YEAR here would
        // quietly make that page accept a year and answer it as a month.
        assertEquals(listOf("WEEK", "MONTH"), DashboardPeriodType.entries.map { it.name })
    }

    @Test
    fun `a year is this month and the twelve before it, as local firsts of the month, oldest first`() {
        val months = ServerActivityChart.monthBuckets(now)

        assertEquals(13, months.size)
        assertEquals(months.sorted(), months)
        assertEquals(ServerActivityChart.monthOf(now), months.last())

        months.forEach { month ->
            val calendar = Calendar.getInstance().apply { timeInMillis = month }

            assertEquals(1, calendar[Calendar.DAY_OF_MONTH])
            assertEquals(month, TimeUtil.startOfDay(month), "a local midnight")
        }

        months.zipWithNext().forEach { (earlier, later) ->
            val calendar = Calendar.getInstance().apply { timeInMillis = earlier }

            calendar.add(Calendar.MONTH, 1)

            assertEquals(calendar.timeInMillis, later)
        }
    }

    @Test
    fun `a day belongs to its month even when its key is an hour off midnight`() {
        val months = ServerActivityChart.monthBuckets(now)
        val firstOfLast = months.last()

        assertEquals(firstOfLast, ServerActivityChart.monthOf(firstOfLast))
        // An hour early would be the last day of the month before on a naive reading.
        assertEquals(firstOfLast, ServerActivityChart.monthOf(firstOfLast - 60L * 60L * 1000L))
        assertEquals(firstOfLast, ServerActivityChart.monthOf(firstOfLast + 60L * 60L * 1000L))
    }

    @Test
    fun `a month's peak is its highest day and its average the mean over the days that have data`() {
        val months = ServerActivityChart.monthBuckets(now)
        val month = months[5]

        val day = { index: Int ->
            val calendar = Calendar.getInstance().apply { timeInMillis = month }

            calendar.add(Calendar.DAY_OF_MONTH, index)

            calendar.timeInMillis
        }

        val days = mapOf(
            day(0) to PlayerActivity(12, 4.0),
            day(3) to PlayerActivity(30, 6.0),
            day(10) to PlayerActivity(7, 1.5)
        )

        val peaks = ServerActivityChart.monthlyPeaks(months, days)
        val averages = ServerActivityChart.monthlyAverages(months, days)

        assertEquals(30L, peaks[month])
        // (4 + 6 + 1.5) / 3 = 3.833…, over the three days with data, not the whole month.
        assertEquals(3.8, averages[month])

        // Every other month is present, at zero.
        assertEquals(13, peaks.size)
        assertEquals(13, averages.size)
        months.filter { it != month }.forEach {
            assertEquals(0L, peaks[it])
            assertEquals(0.0, averages[it])
        }
    }

    @Test
    fun `days outside the year are not counted`() {
        val months = ServerActivityChart.monthBuckets(now)
        val longAgo = Calendar.getInstance().apply {
            timeInMillis = months.first()
            add(Calendar.MONTH, -2)
        }.timeInMillis

        val peaks = ServerActivityChart.monthlyPeaks(months, mapOf(longAgo to PlayerActivity(99, 9.0)))

        assertNull(peaks[longAgo])
        assertTrue(peaks.values.all { it == 0L })
    }

    @Test
    fun `the hour view is the last sixty minutes, the day view the last twenty-four hours`() {
        val minutes = ServerActivityChart.recentBuckets(now, ServerActivityChart.MINUTE_MILLIS, 60)
        val hours = ServerActivityChart.recentBuckets(now, ServerActivityChart.HOUR_MILLIS, 24)

        assertEquals(60, minutes.size)
        assertEquals(24, hours.size)

        // Oldest first, one bucket apart, each a bucket start, the last one holding now.
        listOf(minutes to ServerActivityChart.MINUTE_MILLIS, hours to ServerActivityChart.HOUR_MILLIS).forEach { (buckets, size) ->
            assertEquals(buckets.sorted(), buckets)
            buckets.zipWithNext().forEach { (a, b) -> assertEquals(size, b - a) }
            buckets.forEach { assertEquals(0L, it % size) }
            assertTrue(now >= buckets.last() && now < buckets.last() + size)
        }
    }

    @Test
    fun `minute and hour buckets are filled by exact key, zero where empty`() {
        val buckets = ServerActivityChart.recentBuckets(now, ServerActivityChart.MINUTE_MILLIS, 60)
        val measured = mapOf(buckets[10] to PlayerActivity(7, 7.0), buckets.last() to PlayerActivity(3, 2.45))

        val peaks = ServerActivityChart.peaksAt(buckets, measured)
        val averages = ServerActivityChart.averagesAt(buckets, measured)

        assertEquals(60, peaks.size)
        assertEquals(7L, peaks[buckets[10]])
        assertEquals(3L, peaks[buckets.last()])
        assertEquals(2.5, averages[buckets.last()])
        assertEquals(0L, peaks[buckets[11]])
        assertEquals(0.0, averages[buckets.first()])

        // A row off the grid is not snapped anywhere: minute keys are exact, unlike days.
        assertTrue(ServerActivityChart.peaksAt(buckets, mapOf(buckets[5] + 1 to PlayerActivity(9, 9.0))).values.all { it == 0L })
    }

    @Test
    fun `hour and day are periods of their own`() {
        assertEquals(ServerActivityPeriod.HOUR, ServerActivityPeriod.fromName("HOUR"))
        assertEquals(ServerActivityPeriod.DAY, ServerActivityPeriod.fromName("DAY"))
        assertEquals(listOf("HOUR", "DAY", "WEEK", "MONTH", "YEAR"), ServerActivityPeriod.entries.map { it.name })
    }
}
