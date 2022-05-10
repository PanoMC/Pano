package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface SystemPropertyDao : Dao<SystemProperty> {
    fun add(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun update(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isPropertyExists(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isUserInstalledSystemByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (isUserInstalledSystem: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getValue(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (systemProperty: SystemProperty?, asyncResult: AsyncResult<*>) -> Unit
    )
}