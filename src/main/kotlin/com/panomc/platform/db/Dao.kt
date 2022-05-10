package com.panomc.platform.db

import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

interface Dao<T> {
    fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit
}