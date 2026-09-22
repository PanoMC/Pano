package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.schedule.ScheduleRunStatus
import io.vertx.core.json.JsonObject

/**
 * A recurring job on one server: when it fires, and what it does when it does.
 *
 * The tasks live in their own table because a schedule is an ordered list of them — "warn, back
 * up, restart" is three rows whose order is the whole point — and an ordered list does not belong
 * in a JSON column somebody will one day want to query.
 *
 * [timezone] is stored per schedule rather than taken from the platform because the person who
 * writes "restart at 4 a.m." means four in the morning where their players are, and a server that
 * silently shifts by an hour twice a year is a bug report nobody can reproduce.
 */
data class ServerSchedule(
    val id: Long = -1,
    /** Id shared with the node, which only ever refers to a schedule by this. */
    val uuid: String,
    val serverId: Long,
    var name: String,
    var cron: String,
    var timezone: String,
    var enabled: Boolean = true,
    /** Minutes of countdown before a power task; 0 means no warning at all. */
    var warnMinutes: Int = 0,
    var lastRunAt: Long? = null,
    var lastStatus: ScheduleRunStatus? = null,
    var lastError: String? = null,
    var nextRunAt: Long? = null,
    val createdBy: Long,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) : DBEntity() {
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this)

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is ServerSchedule && other.id == this.id
}
