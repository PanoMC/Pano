package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerScheduleTask
import io.vertx.sqlclient.SqlClient

abstract class ServerScheduleTaskDao : Dao<ServerScheduleTask>(ServerScheduleTask::class.java) {
    abstract suspend fun add(serverScheduleTask: ServerScheduleTask, sqlClient: SqlClient): Long

    /** In `position` order, which is the order they have to run in. */
    abstract suspend fun getAllByScheduleId(scheduleId: Long, sqlClient: SqlClient): List<ServerScheduleTask>

    abstract suspend fun getAllByScheduleIds(
        scheduleIds: List<Long>,
        sqlClient: SqlClient
    ): List<ServerScheduleTask>

    abstract suspend fun deleteByScheduleId(scheduleId: Long, sqlClient: SqlClient)
}
