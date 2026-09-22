package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ServerTaskStatus
import io.vertx.sqlclient.SqlClient

abstract class ServerTaskDao : Dao<ServerTask>(ServerTask::class.java) {
    abstract suspend fun add(
        serverTask: ServerTask,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): ServerTask?

    abstract suspend fun getByUuid(
        uuid: String,
        sqlClient: SqlClient
    ): ServerTask?

    abstract suspend fun getAllByServerId(
        serverId: Long,
        sqlClient: SqlClient
    ): List<ServerTask>

    /**
     * Every task that has not reached an end state yet.
     *
     * Read on a timer by the timeout sweep, which is why it is a query and not a scan of the whole
     * table: unfinished tasks are a handful at any moment while the table keeps weeks of history.
     */
    abstract suspend fun getAllUnfinished(
        sqlClient: SqlClient
    ): List<ServerTask>

    abstract suspend fun updateProgressByUuid(
        uuid: String,
        status: ServerTaskStatus,
        percent: Int,
        message: String?,
        error: String?,
        updatedAt: Long,
        sqlClient: SqlClient
    )

    /** Detaches finished tasks from a server row that is about to disappear. */
    abstract suspend fun clearServerIdByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByNodeId(
        nodeId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteOlderThan(
        time: Long,
        sqlClient: SqlClient
    )
}
