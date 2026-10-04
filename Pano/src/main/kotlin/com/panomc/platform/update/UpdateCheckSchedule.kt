package com.panomc.platform.update

import com.panomc.platform.util.UpdatePeriod
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * When the periodic update check is due.
 *
 * The user-visible semantics stay "once per day / week / month": a check is due once the calendar
 * period of the last check is over. On top of that every Pano draws a random [jitterMs] at start
 * and waits that long into the new period, so instances behind one IP (or in one time zone) do not
 * all ask at the same minute after midnight. A Pano that never checked waits [startupDelayMs] after
 * start, so a fleet restarted together does not ask together either.
 */
class UpdateCheckSchedule(
    val jitterMs: Long = Random.nextLong(0, MAX_JITTER_MS),
    val startupDelayMs: Long = Random.nextLong(0, MAX_STARTUP_DELAY_MS),
    private val startedAt: Long = System.currentTimeMillis(),
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    fun isDue(period: UpdatePeriod, lastCheckMillis: Long?, nowMillis: Long): Boolean {
        if (period == UpdatePeriod.NEVER) return false

        if (lastCheckMillis == null) {
            return nowMillis >= startedAt + startupDelayMs
        }

        val nextPeriodStart = nextPeriodStart(period, lastCheckMillis, zone) ?: return false

        return nowMillis >= nextPeriodStart + jitterMs
    }

    companion object {
        /** Upper bound (exclusive) of the per-instance offset into a new period: two hours. */
        const val MAX_JITTER_MS = 2 * 60 * 60 * 1000L

        /** Upper bound (exclusive) of the wait before a Pano's very first check: five minutes. */
        const val MAX_STARTUP_DELAY_MS = 5 * 60 * 1000L

        /** The start of the calendar period after the one [lastCheckMillis] falls in. */
        fun nextPeriodStart(period: UpdatePeriod, lastCheckMillis: Long, zone: ZoneId): Long? {
            val last = Instant.ofEpochMilli(lastCheckMillis).atZone(zone).toLocalDate()

            val next = when (period) {
                UpdatePeriod.ONCE_PER_DAY -> last.plusDays(1)
                UpdatePeriod.ONCE_PER_WEEK -> last.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
                UpdatePeriod.ONCE_PER_MONTH -> last.withDayOfMonth(1).plusMonths(1)
                UpdatePeriod.NEVER -> return null
            }

            return next.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}
