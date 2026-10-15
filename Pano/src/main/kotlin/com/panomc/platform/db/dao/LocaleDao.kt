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

    /** The languages that are on: what visitors and the language pickers are offered. */
    abstract suspend fun getAllEnabled(
        sqlClient: SqlClient
    ): List<Locale>

    abstract suspend fun getAllByPage(
        limit: Int,
        offset: Long,
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

    /** Whether [code] names a language that exists and is on, so it may be picked as a display language. */
    abstract suspend fun isEnabledByCode(
        code: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsByCode(
        code: String,
        sqlClient: SqlClient
    ): Boolean
}