package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Server
import io.vertx.sqlclient.SqlConnection

interface ServerDao : Dao<Server> {
    suspend fun add(
        server: Server,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Server?

    suspend fun count(
        sqlConnection: SqlConnection
    ): Long
}