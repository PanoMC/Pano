package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface SchemeVersionDao : Dao<SchemeVersion> {
    suspend fun add(
        sqlConnection: SqlConnection,
        schemeVersion: SchemeVersion
    )

    suspend fun getLastSchemeVersion(
        sqlConnection: SqlConnection
    ): SchemeVersion?
}