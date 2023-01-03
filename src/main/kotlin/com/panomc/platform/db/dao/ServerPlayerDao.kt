package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerPlayer
import io.vertx.sqlclient.SqlConnection

interface ServerPlayerDao : Dao<ServerPlayer> {
    suspend fun add(
        serverPlayer: ServerPlayer,
        sqlConnection: SqlConnection
    ): Long

    suspend fun deleteByUsernameAndServerId(
        username: String,
        serverId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Boolean
}