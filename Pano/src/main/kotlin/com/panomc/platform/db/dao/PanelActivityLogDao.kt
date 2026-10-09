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
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun byUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun getAll(
        limit: Int,
        offset: Long,
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
     * only entries older than it. At most [PanelActivityLogDao.MAX_PAGE_SIZE] plus one rows come back,
     * so a caller that asks for one more than it shows learns whether an older page exists.
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

    companion object {
        /** Hard ceiling on one page of server activity, whatever the caller asks for. */
        const val MAX_PAGE_SIZE = 200
    }
}
