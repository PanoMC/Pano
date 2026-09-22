package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.sqlclient.SqlClient

abstract class PanelActivityLogDao : Dao<PanelActivityLog>(PanelActivityLog::class.java) {
    abstract suspend fun add(
        panelActivityLog: PanelActivityLog,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun byUserId(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun byUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun getAll(
        page: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun count(
        userId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    /**
     * One page of the entries belonging to a single server, newest first (§2.4.12).
     *
     * [types] narrows to the server-related log types; [beforeId] is the paging cursor and returns
     * only entries older than it.
     */
    abstract suspend fun byServerId(
        serverId: Long,
        types: List<String>,
        limit: Int,
        beforeId: Long?,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    /** How many entries of one type were written on or after [since]. */
    abstract suspend fun countByTypeSince(
        type: String,
        since: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}