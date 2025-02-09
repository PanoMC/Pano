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

    abstract suspend fun getAll(
        page: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog>

    abstract suspend fun count(
        userId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long
}