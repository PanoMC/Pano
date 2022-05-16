package com.panomc.platform.db

import io.vertx.sqlclient.SqlConnection

interface Dao<T> {
    suspend fun init(sqlConnection: SqlConnection)
}