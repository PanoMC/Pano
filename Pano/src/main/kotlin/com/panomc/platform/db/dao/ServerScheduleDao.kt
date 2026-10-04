package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerSchedule
import com.panomc.platform.server.schedule.ScheduleRunStatus
import io.vertx.sqlclient.SqlClient

abstract class ServerScheduleDao : Dao<ServerSchedule>(ServerSchedule::class.java) {
    abstract suspend fun add(serverSchedule: ServerSchedule, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): ServerSchedule?

    abstract suspend fun getByUuid(uuid: String, sqlClient: SqlClient): ServerSchedule?

    abstract suspend fun getAllByServerId(serverId: Long, sqlClient: SqlClient): List<ServerSchedule>

    /** Every enabled schedule of every server, which is what one timer tick has to consider. */
    abstract suspend fun getAllEnabled(sqlClient: SqlClient): List<ServerSchedule>

    abstract suspend fun update(serverSchedule: ServerSchedule, sqlClient: SqlClient)

    abstract suspend fun updateEnabledById(id: Long, enabled: Boolean, nextRunAt: Long?, sqlClient: SqlClient)

    abstract suspend fun updateRunResultById(
        id: Long,
        lastRunAt: Long,
        lastStatus: ScheduleRunStatus,
        lastError: String?,
        nextRunAt: Long?,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient)
}
