package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ResourceHash
import io.vertx.sqlclient.SqlClient

abstract class ResourceHashDao : Dao<ResourceHash>(ResourceHash::class.java) {
    abstract suspend fun add(
        resourceHash: ResourceHash,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun byListOfHash(
        hashList: List<String>,
        sqlClient: SqlClient
    ): Map<String, ResourceHash>
}