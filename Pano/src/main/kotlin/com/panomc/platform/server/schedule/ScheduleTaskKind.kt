package com.panomc.platform.server.schedule

/**
 * What one step of a schedule does.
 *
 * [BACKUP] exists only for managed servers: a backup is taken by the node from the server's
 * directory, and a linked server has no directory Pano can reach. That is refused when the
 * schedule is saved rather than when it fires, so nobody finds out at 4 a.m.
 */
enum class ScheduleTaskKind {
    POWER,
    COMMAND,
    BACKUP;

    companion object {
        fun fromId(id: String?): ScheduleTaskKind? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}
