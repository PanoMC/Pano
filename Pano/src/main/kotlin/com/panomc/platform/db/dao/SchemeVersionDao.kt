package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlClient

abstract class SchemeVersionDao : Dao<SchemeVersion>(SchemeVersion::class.java) {
    abstract suspend fun add(
        sqlClient: SqlClient,
        schemeVersion: SchemeVersion
    )

    abstract suspend fun getLastSchemeVersion(
        sqlClient: SqlClient
    ): SchemeVersion?

    abstract suspend fun getLastSchemeVersion(
        pluginId: String,
        sqlClient: SqlClient
    ): SchemeVersion?

    abstract suspend fun deleteByPluginId(
        pluginId: String,
        sqlClient: SqlClient
    )

    abstract suspend fun getAllPluginIds(
        sqlClient: SqlClient
    ): List<String>
}