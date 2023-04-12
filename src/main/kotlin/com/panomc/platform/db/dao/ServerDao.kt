package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.sqlclient.SqlClient

interface ServerDao : Dao<Server> {
    suspend fun add(
        server: Server,
        sqlClient: SqlClient
    ): Long

    suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Server?

    suspend fun getAllByPermissionGranted(
        sqlClient: SqlClient
    ): List<Server>

    suspend fun countOfPermissionGranted(
        sqlClient: SqlClient
    ): Long

    suspend fun count(
        sqlClient: SqlClient
    ): Long

    suspend fun updateStatusById(
        id: Long,
        status: ServerStatus,
        sqlClient: SqlClient
    )

    suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlClient: SqlClient
    )

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun updatePlayerCountById(
        id: Long,
        playerCount: Int,
        sqlClient: SqlClient
    )

    suspend fun updateStartTimeById(
        id: Long,
        startTime: Long,
        sqlClient: SqlClient
    )

    suspend fun updateStopTimeById(
        id: Long,
        stopTime: Long,
        sqlClient: SqlClient
    )

    suspend fun updateAcceptedTimeById(
        id: Long,
        acceptedTime: Long,
        sqlClient: SqlClient
    )

    suspend fun updateServerForOfflineById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun updateById(
        id: Long,
        name: String,
        motd: String,
        host: String,
        port: Int,
        playerCount: Long,
        maxPlayerCount: Long,
        type: ServerType,
        version: String,
        favicon: String,
        status: ServerStatus,
        startTime: Long,
        sqlClient: SqlClient
    )
}