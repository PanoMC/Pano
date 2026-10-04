package com.panomc.platform.server.schedule

/**
 * When to tell the players that the server is about to go down.
 *
 * A stop that arrives with no warning is the single most annoying thing an automated schedule can
 * do to whoever is mid-build, so a schedule that powers a server off counts down first. The
 * offsets are the configured lead time plus the two everybody expects — five minutes and one
 * minute — deduplicated and ordered from earliest to latest, so a `warnMinutes` of 5 does not
 * announce five minutes twice and a `warnMinutes` of 1 does not announce the future.
 *
 * Pure and in its own class because it is shared reasoning: Pano applies it to linked servers and
 * the node applies its own copy to managed ones.
 */
object ScheduleWarnings {
    const val MAX_WARN_MINUTES = 60

    /** The default countdown when a schedule does not ask for one of its own. */
    const val DEFAULT_WARN_MINUTES = 5

    private val STANDARD_OFFSETS = listOf(5, 1)

    /**
     * Minutes before the run at which a warning should be sent, earliest first.
     *
     * Empty when [warnMinutes] is zero or less: that is how a schedule says "no countdown", and
     * adding the standard offsets to it would override an explicit choice.
     */
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

    /** The message a countdown writes to the server console. */
    fun message(minutes: Int, restarting: Boolean): String {
        val what = if (restarting) "restarts" else "stops"
        val unit = if (minutes == 1) "minute" else "minutes"

        return "say Server $what in $minutes $unit"
    }
}
