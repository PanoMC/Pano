package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Notification
import io.vertx.sqlclient.SqlConnection

interface NotificationDao : Dao<Notification> {
    suspend fun add(
        notification: Notification,
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
    ): List<Notification>

    suspend fun get10ByUserIdAndStartFromId(
        userId: Long,
        notificationId: Long,
        sqlConnection: SqlConnection
    ): List<Notification>

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
    ): List<Notification>

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
    ): Notification?

    suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun markReadById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun deleteAllByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    )
}