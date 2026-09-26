package com.panomc.platform.backup

import io.vertx.core.json.JsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * Local backup settings, stored in the database (`system_property` option [OPTION]) rather than in
 * config.conf, so they travel with the site's data and need no config migration.
 */
data class PanoBackupSettings(
    val schedule: Schedule = Schedule.OFF,
    /** Local hour (0-23) from which a due scheduled backup is taken. */
    val hour: Int = 4,
    /** How many scheduled backups are kept. */
    val keep: Int = 7
) {
    enum class Schedule(val intervalMs: Long) {
        OFF(0),
        DAILY(24L * 60 * 60 * 1000),
        WEEKLY(7L * 24 * 60 * 60 * 1000)
    }

    fun toJson(): JsonObject = JsonObject().put("schedule", schedule.name).put("hour", hour).put("keep", keep)

    /**
     * Whether a scheduled backup is due at [now]: the schedule is on, the local hour has reached
     * [hour], and the last scheduled backup is at least one interval (minus an hour of slack for
     * tick jitter) old.
     */
    fun isDue(lastScheduledAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (schedule == Schedule.OFF) {
            return false
        }

        if (Instant.ofEpochMilli(now).atZone(zone).hour < hour) {
            return false
        }

        return lastScheduledAt == null || now - lastScheduledAt >= schedule.intervalMs - SLACK_MS
    }

    companion object {
        const val OPTION = "pano_backup_settings"
        const val MAX_KEEP = 50

        private const val SLACK_MS = 60L * 60 * 1000

        /** Parses stored or submitted settings; null for anything invalid. */
        fun fromJson(json: JsonObject?): PanoBackupSettings? {
            if (json == null) {
                return null
            }

            val schedule = Schedule.values().firstOrNull { it.name == json.getValue("schedule") } ?: return null
            val hour = (json.getValue("hour") as? Number)?.toInt() ?: return null
            val keep = (json.getValue("keep") as? Number)?.toInt() ?: return null

            if (hour !in 0..23 || keep !in 1..MAX_KEEP) {
                return null
            }

            return PanoBackupSettings(schedule, hour, keep)
        }

        fun parse(value: String?): PanoBackupSettings = try {
            fromJson(value?.let { JsonObject(it) }) ?: PanoBackupSettings()
        } catch (_: Exception) {
            PanoBackupSettings()
        }
    }
}
