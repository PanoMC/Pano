package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import io.vertx.sqlclient.SqlClient

abstract class ServerDao : Dao<Server>(Server::class.java) {
    abstract suspend fun add(
        server: Server,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Server?

    abstract suspend fun getAllByPermissionGranted(
        sqlClient: SqlClient
    ): List<Server>

    abstract suspend fun countOfPermissionGranted(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun updateStatusById(
        id: Long,
        status: ServerStatus,
        sqlClient: SqlClient
    )

    abstract suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlClient: SqlClient
    )

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updatePlayerCountById(
        id: Long,
        playerCount: Int,
        sqlClient: SqlClient
    )

    abstract suspend fun updateStartTimeById(
        id: Long,
        startTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateStopTimeById(
        id: Long,
        stopTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateAcceptedTimeById(
        id: Long,
        acceptedTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateServerForOfflineById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun update(
        server: Server,
        sqlClient: SqlClient
    )

    abstract suspend fun updateSettingsById(
        serverSettings: Server.Companion.ServerSettings,
        id: Long,
        sqlClient: SqlClient
    )
}