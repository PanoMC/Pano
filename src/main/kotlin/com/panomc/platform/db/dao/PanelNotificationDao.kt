package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelNotification
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PanelNotificationDao : Dao<PanelNotification> {
    suspend fun add(
        panelNotification: PanelNotification,
        sqlConnection: SqlConnection
    )

    suspend fun getCountOfNotReadByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getCountByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getLast10ByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun get10ByUserIdAndStartFromId(
        userId: Long,
        notificationId: Long,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun markReadLast10(
        userId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun markReadLast10StartFromId(
        userId: Long,
        notificationId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun getLast5ByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun markReadLast5ByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun existsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): PanelNotification?

    suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun deleteAllByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    )
}