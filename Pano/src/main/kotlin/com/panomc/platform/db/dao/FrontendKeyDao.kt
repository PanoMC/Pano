package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.FrontendKey
import io.vertx.sqlclient.SqlClient

abstract class FrontendKeyDao : Dao<FrontendKey>(FrontendKey::class.java) {
    /** Stores [frontendKey] and returns the new id. */
    abstract suspend fun add(frontendKey: FrontendKey, sqlClient: SqlClient): Long

    abstract suspend fun getAll(sqlClient: SqlClient): List<FrontendKey>

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): FrontendKey?

    abstract suspend fun count(sqlClient: SqlClient): Int

    /** Returns whether a row was deleted. */
    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, sqlClient: SqlClient)
}
