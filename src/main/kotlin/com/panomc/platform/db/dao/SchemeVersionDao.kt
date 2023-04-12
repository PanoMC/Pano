package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlClient

interface SchemeVersionDao : Dao<SchemeVersion> {
    suspend fun add(
        sqlClient: SqlClient,
        schemeVersion: SchemeVersion
    )

    suspend fun getLastSchemeVersion(
        sqlClient: SqlClient
    ): SchemeVersion?
}