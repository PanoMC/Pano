package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.BannedIp
import io.vertx.sqlclient.SqlClient

abstract class BannedIpDao : Dao<BannedIp>(BannedIp::class.java) {
    abstract suspend fun add(
        bannedIp: BannedIp,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun upsert(
        bannedIp: BannedIp,
        sqlClient: SqlClient
    )

    abstract suspend fun existsByIp(
        ip: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getActiveByIp(
        ip: String,
        sqlClient: SqlClient
    ): BannedIp?

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): BannedIp?

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countBySearch(
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<BannedIp>

    abstract suspend fun getAllByPageAndSearch(
        page: Long,
        search: String,
        sqlClient: SqlClient
    ): List<BannedIp>

    abstract suspend fun countByListFilter(
        listFilter: BannedIpListFilter,
        nowMs: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByListFilterAndSearch(
        listFilter: BannedIpListFilter,
        search: String,
        nowMs: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllByPageAndListFilter(
        page: Long,
        listFilter: BannedIpListFilter,
        nowMs: Long,
        sqlClient: SqlClient
    ): List<BannedIp>

    abstract suspend fun getAllByPageAndListFilterAndSearch(
        page: Long,
        search: String,
        listFilter: BannedIpListFilter,
        nowMs: Long,
        sqlClient: SqlClient
    ): List<BannedIp>

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )
}
