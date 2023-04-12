package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerPlayer
import io.vertx.sqlclient.SqlClient

interface ServerPlayerDao : Dao<ServerPlayer> {
    suspend fun add(
        serverPlayer: ServerPlayer,
        sqlClient: SqlClient
    ): Long

    suspend fun deleteByUsernameAndServerId(
        username: String,
        serverId: Long,
        sqlClient: SqlClient
    )

    suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )
}