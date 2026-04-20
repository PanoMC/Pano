package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.BanHistory
import io.vertx.sqlclient.SqlClient

abstract class BanHistoryDao : Dao<BanHistory>(BanHistory::class.java) {
    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countBySearch(
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun add(
        banHistory: BanHistory,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByUserId(
        id: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<BanHistory>

    abstract suspend fun getAllByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<BanHistory>

    abstract suspend fun getAllByPageAndSearch(
        page: Long,
        search: String,
        sqlClient: SqlClient
    ): List<BanHistory>

    abstract suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}