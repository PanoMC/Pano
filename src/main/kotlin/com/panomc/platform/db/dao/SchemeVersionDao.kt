package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlConnection

interface SchemeVersionDao : Dao<SchemeVersion> {
    suspend fun add(
        sqlConnection: SqlConnection,
        schemeVersion: SchemeVersion
    )

    suspend fun getLastSchemeVersion(
        sqlConnection: SqlConnection
    ): SchemeVersion?
}