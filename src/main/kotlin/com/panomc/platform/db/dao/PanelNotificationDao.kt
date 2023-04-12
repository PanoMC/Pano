package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelNotification
import io.vertx.sqlclient.SqlClient

interface PanelNotificationDao : Dao<PanelNotification> {
    suspend fun add(
        panelNotification: PanelNotification,
        sqlClient: SqlClient
    )

    suspend fun addAll(
        panelNotifications: List<PanelNotification>,
        sqlClient: SqlClient
    )

    suspend fun getCountOfNotReadByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun getCountByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun getLast10ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelNotification>

    suspend fun get10ByUserIdAndStartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    ): List<PanelNotification>

    suspend fun markReadLast10(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun markReadLast10StartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    )

    suspend fun getLast5ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelNotification>

    suspend fun markReadLast5ByUserId(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): PanelNotification?

    suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun markReadById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun deleteAllByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}