package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Locale
import io.vertx.sqlclient.SqlClient

abstract class LocaleDao : Dao<Locale>(Locale::class.java) {
    abstract suspend fun add(
        locale: Locale,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<Locale>

    abstract suspend fun getAllByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<Locale>

    abstract suspend fun byId(
        id: Long,
        sqlClient: SqlClient
    ): Locale?

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun update(
        locale: Locale,
        sqlClient: SqlClient
    )

    abstract suspend fun getIdByCode(
        code: String,
        sqlClient: SqlClient
    ): Long?

    abstract suspend fun existsByCode(
        code: String,
        sqlClient: SqlClient
    ): Boolean
}