package com.panomc.platform.server.metrics

import com.panomc.platform.server.dto.PlayerActivity
import com.panomc.platform.util.TimeUtil
import java.util.Calendar

/**
 * The day buckets of the Server Activity chart, and how a day's figures are put on them
 * (§2.4.19).
 *
 * Deliberately the same buckets as the Statistics page's Website Activity card, built the same way
 * — local midnights, `0..amountOfDays` days back with today included — because the two charts sit
 * on the same axis in the user's head and a server chart that started its week a day earlier would
 * be read as a different week.
 *
 * Every bucket is always present, with a zero where the database had nothing: a chart drawn from a
 * map with holes in it draws a line through days that never happened.
 */
object ServerActivityChart {
    const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

    /** Seven days plus today, or thirty plus today — exactly what the statistics page uses. */
    fun amountOfDays(period: ServerActivityPeriod) = if (period == ServerActivityPeriod.WEEK) 7 else 30

    /** The Year view's months before the current one: twelve, so thirteen buckets in all (§2.4.24). */
    const val MONTHS_BACK = 12

    /** The local first-of-month midnights the Year view covers, oldest first, ending this month. */
    fun monthBuckets(now: Long, monthsBack: Int = MONTHS_BACK): List<Long> {
        val buckets = mutableListOf<Long>()

        val calendar = Calendar.getInstance()

        calendar.timeInMillis = monthOf(now)

        for (i in 0..monthsBack) {
            buckets.add(0, calendar.timeInMillis)

            calendar.add(Calendar.MONTH, -1)
        }

        return buckets
    }

    /**
     * The local first-of-month the day starting at [day] belongs to.
     *
     * Asked of the middle of the day, like [align], so a day whose key is an hour off its midnight
     * — a clock change, a fixed-offset backfill — still lands in its own month on the 1st.
     */
    fun monthOf(day: Long): Long {
        val calendar = Calendar.getInstance()

        calendar.timeInMillis = TimeUtil.startOfDay(day + DAY_MILLIS / 2)
        calendar[Calendar.DAY_OF_MONTH] = 1

        return calendar.timeInMillis
    }

    /** Each month's peak: the highest of its daily peaks, zero for a month with no days. */
    fun monthlyPeaks(buckets: List<Long>, days: Map<Long, PlayerActivity>): Map<Long, Long> {
        val byMonth = days.entries.groupBy({ monthOf(it.key) }, { it.value })

        return buckets.associateWith { month -> byMonth[month]?.maxOf { it.peak } ?: 0L }
    }

    /**
     * Each month's average: the mean of its daily averages over the days that have data — not over
     * the whole month, because a server that was off for three weeks did not average a quarter of a
     * player — to one decimal, zero for a month with no days.
     */
    fun monthlyAverages(buckets: List<Long>, days: Map<Long, PlayerActivity>): Map<Long, Double> {
        val byMonth = days.entries.groupBy({ monthOf(it.key) }, { it.value })

        return buckets.associateWith { month ->
            byMonth[month]?.takeIf { it.isNotEmpty() }?.let { round(it.sumOf { day -> day.average } / it.size) } ?: 0.0
        }
    }

    /** The local midnights the chart covers, oldest first. */
    fun dayBuckets(now: Long, amountOfDays: Int): List<Long> {
        val buckets = mutableListOf<Long>()

        val calendar = Calendar.getInstance()

        calendar.timeInMillis = now
        calendar[Calendar.HOUR_OF_DAY] = 0
        calendar[Calendar.MINUTE] = 0
        calendar[Calendar.SECOND] = 0
        calendar[Calendar.MILLISECOND] = 0

        for (i in 0..amountOfDays) {
            buckets.add(0, calendar.timeInMillis)

            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        return buckets
    }

    /** The highest reading of each bucket, zero for a day with no rows. */
    fun peaks(buckets: List<Long>, measured: Map<Long, PlayerActivity>): Map<Long, Long> {
        val aligned = align(measured)

        return buckets.associateWith { bucket -> aligned[bucket]?.peak ?: 0L }
    }

    /** The mean reading of each bucket to one decimal, zero for a day with no rows. */
    fun averages(buckets: List<Long>, measured: Map<Long, PlayerActivity>): Map<Long, Double> {
        val aligned = align(measured)

        return buckets.associateWith { bucket -> round(aligned[bucket]?.average ?: 0.0) }
    }

    /** Minute buckets of the Hour view (§2.4.26). */
    const val MINUTE_MILLIS: Long = 60L * 1000L

    /** Hour buckets of the Day view (§2.4.26). */
    const val HOUR_MILLIS: Long = 60L * 60L * 1000L

    /**
     * The last [count] buckets of [bucketMs] up to and including the one [now] is in, oldest first,
     * keyed by bucket start — the Hour view's sixty minutes and the Day view's twenty-four hours.
     * Aligned exactly like [com.panomc.platform.db.implementation.ServerMetricDaoImpl.playerActivityQuery]
     * aligns its rows, so the two meet on the same keys.
     */
    fun recentBuckets(now: Long, bucketMs: Long, count: Int): List<Long> {
        val current = Math.floorDiv(now, bucketMs) * bucketMs

        return (count - 1 downTo 0).map { current - it * bucketMs }
    }

    /** The peak of each bucket in [buckets], zero where [measured] has none. Keys are matched exactly. */
    fun peaksAt(buckets: List<Long>, measured: Map<Long, PlayerActivity>): Map<Long, Long> =
        buckets.associateWith { bucket -> measured[bucket]?.peak ?: 0L }

    /** The average of each bucket in [buckets] to one decimal, zero where [measured] has none. */
    fun averagesAt(buckets: List<Long>, measured: Map<Long, PlayerActivity>): Map<Long, Double> =
        buckets.associateWith { bucket -> round(measured[bucket]?.average ?: 0.0) }

    /** One decimal, because a tooltip reading "4.5 players" is the point and 4.499999 is not. */
    fun round(value: Double): Double = Math.round(value * 10.0) / 10.0

    /**
     * The bucket each measured day actually belongs to.
     *
     * The query groups by a fixed timezone offset, which is right for every day of the year except
     * the two the clocks move on: a 23 or 25 hour day comes back an hour either side of its own
     * midnight and would miss the bucket the chart drew. Asking [TimeUtil.startOfDay] which day
     * the *middle* of it falls in puts it back, and leaves every ordinary day exactly where it was.
     */
    private fun align(measured: Map<Long, PlayerActivity>): Map<Long, PlayerActivity> =
        measured.mapKeys { entry -> TimeUtil.startOfDay(entry.key + DAY_MILLIS / 2) }
}
