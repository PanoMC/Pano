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

    suspend fun getCountOfNotReadByUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getCountByUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getLast10ByUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun get10ByUserIDAndStartFromID(
        userID: Int,
        notificationID: Int,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun markReadLast10(
        userID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun markReadLast10StartFromID(
        userID: Int,
        notificationID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun getLast5ByUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): List<PanelNotification>

    suspend fun markReadLat5ByUserID(
        userID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun existsByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getByID(
        id: Int,
        sqlConnection: SqlConnection
    ): PanelNotification?

    suspend fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun deleteAllByUserID(
        userID: Int,
        sqlConnection: SqlConnection
    )
}