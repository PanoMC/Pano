package com.panomc.platform.server.schedule

/** How a schedule's last run ended. */
enum class ScheduleRunStatus {
    OK,
    FAILED,
    /** The run was due but could not start: the server was offline, or its node was. */
    SKIPPED;

    companion object {
        fun fromId(id: String?): ScheduleRunStatus? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}
