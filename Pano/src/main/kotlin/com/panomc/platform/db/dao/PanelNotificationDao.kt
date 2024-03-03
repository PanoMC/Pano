package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelNotification
import io.vertx.sqlclient.SqlClient

abstract class PanelNotificationDao : Dao<PanelNotification>(PanelNotification::class.java) {
    abstract suspend fun add(
        panelNotification: PanelNotification,
        sqlClient: SqlClient
    )

    abstract suspend fun addAll(
        panelNotifications: List<PanelNotification>,
        sqlClient: SqlClient
    )

    abstract suspend fun getCountOfNotReadByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getCountByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getLast10ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelNotification>

    abstract suspend fun get10ByUserIdAndStartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    ): List<PanelNotification>

    abstract suspend fun markReadLast10(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun markReadLast10StartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getLast5ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelNotification>

    abstract suspend fun markReadLast5ByUserId(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): PanelNotification?

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun markReadById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteAllByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}