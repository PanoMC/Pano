package com.panomc.platform.db

import io.vertx.sqlclient.SqlClient

interface Dao<T> {
    suspend fun init(sqlClient: SqlClient)
}