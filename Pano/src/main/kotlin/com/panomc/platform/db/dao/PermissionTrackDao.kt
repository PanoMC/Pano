package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionTrack
import io.vertx.sqlclient.SqlClient

abstract class PermissionTrackDao : Dao<PermissionTrack>(PermissionTrack::class.java) {
    abstract suspend fun add(
        permissionTrack: PermissionTrack,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<PermissionTrack>
}

