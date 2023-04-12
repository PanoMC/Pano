package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SystemProperty
import io.vertx.sqlclient.SqlClient

interface SystemPropertyDao : Dao<SystemProperty> {
    suspend fun add(
        systemProperty: SystemProperty,
        sqlClient: SqlClient
    )

    suspend fun update(
        option: String,
        value: String,
        sqlClient: SqlClient
    )

    suspend fun existsByOption(
        option: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isUserInstalledSystemByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun getByOption(
        option: String,
        sqlClient: SqlClient
    ): SystemProperty?
}