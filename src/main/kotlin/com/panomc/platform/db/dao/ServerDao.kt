package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.sqlclient.SqlConnection

interface ServerDao : Dao<Server> {
    suspend fun add(
        server: Server,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Server?

    suspend fun getAllByPermissionGranted(
        sqlConnection: SqlConnection
    ): List<Server>

    suspend fun countOfPermissionGranted(
        sqlConnection: SqlConnection
    ): Long

    suspend fun count(
        sqlConnection: SqlConnection
    ): Long

    suspend fun updateStatusById(
        id: Long,
        status: ServerStatus,
        sqlConnection: SqlConnection
    )

    suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlConnection: SqlConnection
    )

    suspend fun existsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun updatePlayerCountById(
        id: Long,
        playerCount: Int,
        sqlConnection: SqlConnection
    )

    suspend fun updateStartTimeById(
        id: Long,
        startTime: Long,
        sqlConnection: SqlConnection
    )

    suspend fun updateStopTimeById(
        id: Long,
        stopTime: Long,
        sqlConnection: SqlConnection
    )

    suspend fun updateServerForOfflineById(
        id: Long,
        sqlConnection: SqlConnection
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
        sqlConnection: SqlConnection
    )
}