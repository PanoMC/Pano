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
        option: String,
        value: String,
        sqlConnection: SqlConnection
    )

    suspend fun existsByOption(
        option: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isUserInstalledSystemByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getByOption(
        option: String,
        sqlConnection: SqlConnection
    ): SystemProperty?
}