package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Notification
import io.vertx.sqlclient.SqlConnection

interface NotificationDao : Dao<Notification> {
    suspend fun add(
        notification: Notification,
        sqlConnection: SqlConnection
    )

    suspend fun getLast5ByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): List<Notification>

    suspend fun getCountOfNotReadByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun markReadLast5ByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    )
}