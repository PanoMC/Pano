package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SchemeVersion
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface SchemeVersionDao : Dao<SchemeVersion> {
    fun add(schemeVersion: SchemeVersion, handler: (result: Result?) -> Unit)

    fun add(
        sqlConnection: SqlConnection,
        schemeVersion: SchemeVersion,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getLastSchemeVersion(
        sqlConnection: SqlConnection,
        handler: (schemeVersion: SchemeVersion?, asyncResult: AsyncResult<*>) -> Unit
    )
}