package com.panomc.platform.server.metrics

import com.panomc.platform.util.TimeUtil
import java.util.TimeZone

/**
 * The bucket size a caller may ask for instead of the range's own (`bucket=minute|hour|day`,
 * §2.4.25): the Performance card's Week view, for one, is `range=7d&bucket=day`.
 */
enum class MetricsBucket(val id: String, val ms: Long) {
    MINUTE("minute", 60L * 1000L),
    HOUR("hour", 60L * 60L * 1000L),
    DAY("day", 24L * 60L * 60L * 1000L);

    companion object {
        /** The bucket [id] names, or null for none (the range's own). */
        fun fromId(id: String?): MetricsBucket? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The window one metrics series is read over: a [range], its bucket size — the range's own or a
 * [MetricsBucket] override — and whether buckets are Pano-local days (§2.4.25).
 *
 * Every other bucket stays aligned exactly as it always was (a plain `FLOOR(ts / bucket)`), which is
 * what "buckets stay aligned to Pano's zone as today" means for sizes up to six hours. A day bucket
 * cannot be: a UTC day is not the operator's day, so those are cut at Pano's local midnight, the way
 * the Server Activity chart cuts its days.
 */
data class MetricsSeriesWindow(val range: MetricsRange, val bucketMs: Long, val localDays: Boolean) {
    /**
     * The shift the query applies before bucketing: Pano's zone offset for local days, none for
     * anything else, so every series that existed before keeps the exact same keys.
     */
    fun offsetMs(now: Long): Long = if (localDays) TimeZone.getDefault().getOffset(now).toLong() else 0L

    /**
     * A bucket's key as the chart plots it. A local day is snapped onto its real midnight: the query
     * uses one fixed offset, which is an hour out on the far side of a clock change.
     */
    fun key(bucket: Long): Long = if (localDays) TimeUtil.startOfDay(bucket + MetricsBucket.DAY.ms / 2) else bucket

    companion object {
        /**
         * Most points one series may have. A day of minutes is the largest honest chart; a week of
         * them — ten thousand points per server, times every server on the bulk endpoint — is a
         * request to refuse rather than to answer.
         */
        const val MAX_POINTS = 1_440

        /** The window for [range] with an optional [bucket] override, or null when it has too many points. */
        fun of(range: MetricsRange, bucket: MetricsBucket?): MetricsSeriesWindow? {
            val bucketMs = bucket?.ms ?: range.bucketMs

            if (range.durationMs / bucketMs > MAX_POINTS) {
                return null
            }

            return MetricsSeriesWindow(range, bucketMs, bucket == MetricsBucket.DAY)
        }
    }
}
