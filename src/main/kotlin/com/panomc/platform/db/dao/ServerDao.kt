package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Server
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface ServerDao : Dao<Server> {
    suspend fun add(
        server: Server,
        sqlConnection: SqlConnection
    ): String

    suspend fun count(
        sqlConnection: SqlConnection
    ): Long
}