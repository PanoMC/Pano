package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SystemProperty
import io.vertx.sqlclient.SqlConnection

interface SystemPropertyDao : Dao<SystemProperty> {
    suspend fun add(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    )

    suspend fun update(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    )

    suspend fun isPropertyExists(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isUserInstalledSystemByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getValue(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    ): SystemProperty?
}