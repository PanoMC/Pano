package com.panomc.node.schedule

/**
 * When to tell the players the server is about to go down.
 *
 * The same rule as Pano's own `ScheduleWarnings`, applied here because this is the side that fires
 * a managed server's schedules: the configured lead time plus five and one minute, deduplicated,
 * earliest first, and nothing at all when the schedule asked for no countdown.
 */
object ScheduleWarnings {
    private const val MAX_WARN_MINUTES = 60

    private val STANDARD_OFFSETS = listOf(5, 1)

    fun offsetsFor(warnMinutes: Int): List<Int> {
        if (warnMinutes <= 0) {
            return emptyList()
        }

        val capped = warnMinutes.coerceAtMost(MAX_WARN_MINUTES)

        return (listOf(capped) + STANDARD_OFFSETS)
            .filter { it in 1..capped }
            .distinct()
            .sortedDescending()
    }

    fun message(minutes: Int, restarting: Boolean): String {
        val what = if (restarting) "restarts" else "stops"
        val unit = if (minutes == 1) "minute" else "minutes"

        return "say Server $what in $minutes $unit"
    }
}
