package com.panomc.platform.server.metrics

/**
 * How far back a metrics chart looks and how wide one point on it is.
 *
 * Each range gets a bucket size that keeps the chart between roughly 100 and 200 points, so the
 * 30 day view costs the same on the wire as the 1 hour one and the browser never has to thin
 * 43 200 rows itself.
 *
 * Shared by the single-server endpoint and the bulk one behind the servers modal (§2.4.18 B):
 * `range=1h` has to mean the same window and the same bucket in both, or the sparkline on a card
 * and the chart it opens would be drawn from different data.
 */
enum class MetricsRange(val id: String, val durationMs: Long, val bucketMs: Long) {
    HOUR("1h", 60L * 60L * 1000L, 60L * 1000L),
    /** Twelve hours in five-minute buckets, for the Overview's 12 h sparklines (§2.4.25). */
    HALF_DAY("12h", 12L * 60L * 60L * 1000L, 5L * 60L * 1000L),
    DAY("24h", 24L * 60L * 60L * 1000L, 10L * 60L * 1000L),
    WEEK("7d", 7L * 24L * 60L * 60L * 1000L, 60L * 60L * 1000L),
    MONTH("30d", 30L * 24L * 60L * 60L * 1000L, 6L * 60L * 60L * 1000L);

    companion object {
        /** The range [id] names, or the hour view for anything else, including nothing at all. */
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: HOUR
    }
}
