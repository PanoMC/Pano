package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerPlayer
import io.vertx.sqlclient.SqlClient

abstract class ServerPlayerDao : Dao<ServerPlayer>(ServerPlayer::class.java) {
    abstract suspend fun add(
        serverPlayer: ServerPlayer,
        sqlClient: SqlClient
    ): Long

    /** The whole online roster of one server, as recorded by the join and quit events. */
    abstract suspend fun getAllByServerId(
        serverId: Long,
        sqlClient: SqlClient
    ): List<ServerPlayer>

    abstract suspend fun deleteByUsernameAndServerId(
        username: String,
        serverId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getByUsername(
        username: String,
        sqlClient: SqlClient
    ): List<ServerPlayer>

    abstract suspend fun existsByUsernameList(
        usernameList: List<String>,
        sqlClient: SqlClient
    ): Map<String, Boolean>

    abstract suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )
}