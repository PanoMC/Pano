package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionTrack
import io.vertx.sqlclient.SqlClient

abstract class PermissionTrackDao : Dao<PermissionTrack>(PermissionTrack::class.java) {
    abstract suspend fun add(
        permissionTrack: PermissionTrack,
        sqlClient: SqlClient
    ): Long

    /**
     * Overwrite an existing track's description and group chain, keyed by its id.
     */
    abstract suspend fun update(
        permissionTrack: PermissionTrack,
        sqlClient: SqlClient
    )

    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<PermissionTrack>
}

