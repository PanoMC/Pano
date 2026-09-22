package com.panomc.platform.server.metrics

/**
 * The periods of the Server Activity chart (§2.4.19, §2.4.24).
 *
 * Its own enum rather than a `YEAR` added to the Statistics page's `DashboardPeriodType`: that one
 * is enumerated into the Statistics API's request validation and branched on as "WEEK, or else a
 * month" all through the website statistics, so a new value there would quietly make the
 * Statistics page accept a year and answer it as a month.
 */
enum class ServerActivityPeriod {
    /** The last sixty minutes, minute by minute (§2.4.26). */
    HOUR,

    /** The last twenty-four hours, hour by hour (§2.4.26). */
    DAY,
    WEEK,
    MONTH,
    YEAR;

    companion object {
        /** The period [name] names, or the week view for anything else, including nothing. */
        fun fromName(name: String?): ServerActivityPeriod = entries.firstOrNull { it.name == name } ?: WEEK
    }
}
