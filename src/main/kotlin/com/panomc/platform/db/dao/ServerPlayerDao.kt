package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerPlayer
import io.vertx.sqlclient.SqlClient

abstract class ServerPlayerDao : Dao<ServerPlayer>(ServerPlayer::class.java) {
    abstract suspend fun add(
        serverPlayer: ServerPlayer,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteByUsernameAndServerId(
        username: String,
        serverId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )
}