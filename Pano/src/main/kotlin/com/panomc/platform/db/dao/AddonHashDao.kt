package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.AddonHash
import io.vertx.sqlclient.SqlClient

abstract class AddonHashDao : Dao<AddonHash>(AddonHash::class.java) {
    abstract suspend fun add(
        addonHash: AddonHash,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun byListOfHash(
        hashList: List<String>,
        sqlClient: SqlClient
    ): Map<String, AddonHash>
}