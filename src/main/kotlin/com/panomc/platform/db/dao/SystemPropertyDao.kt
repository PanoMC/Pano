package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SystemProperty
import io.vertx.sqlclient.SqlClient

abstract class SystemPropertyDao : Dao<SystemProperty>(SystemProperty::class.java) {
    abstract suspend fun add(
        systemProperty: SystemProperty,
        sqlClient: SqlClient
    )

    abstract suspend fun update(
        option: String,
        value: String,
        sqlClient: SqlClient
    )

    abstract suspend fun existsByOption(
        option: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isUserInstalledSystemByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getByOption(
        option: String,
        sqlClient: SqlClient
    ): SystemProperty?
}