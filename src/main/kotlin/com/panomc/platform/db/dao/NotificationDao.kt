package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Notification
import io.vertx.sqlclient.SqlClient

abstract class NotificationDao : Dao<Notification>(Notification::class.java) {
    abstract suspend fun add(
        notification: Notification,
        sqlClient: SqlClient
    )

    abstract suspend fun addAll(
        notifications: List<Notification>,
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
    ): List<Notification>

    abstract suspend fun get10ByUserIdAndStartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    ): List<Notification>

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
    ): List<Notification>

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
    ): Notification?

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