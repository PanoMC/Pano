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
        userId: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getCountByUserId(
        userId: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getLast10ByUserId(
        userId: Int,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun get10ByUserIdAndStartFromId(
        userId: Int,
        notificationId: Int,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun markReadLast10(
        userId: Int,
        sqlConnection: SqlConnection
    )

    suspend fun markReadLast10StartFromId(
        userId: Int,
        notificationId: Int,
        sqlConnection: SqlConnection
    )

    suspend fun getLast5ByUserId(
        userId: Int,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun markReadLat5ByUserId(
        userId: Int,
        sqlConnection: SqlConnection
    )

    suspend fun existsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getById(
        id: Int,
        sqlConnection: SqlConnection
    ): PanelNotification?

    suspend fun deleteById(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun deleteAllByUserId(
        userId: Int,
        sqlConnection: SqlConnection
    )
}