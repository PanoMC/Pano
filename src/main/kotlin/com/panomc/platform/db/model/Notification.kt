package com.panomc.platform.db.model

import com.panomc.platform.notification.NotificationStatus
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Notification(
    val id: Long = -1,
    val userId: Long,
    val type: String,
    val properties: JsonObject = JsonObject(),
    val date: Long = System.currentTimeMillis(),
    val status: NotificationStatus = NotificationStatus.NOT_READ
) {
    companion object {
        fun from(row: Row) = Notification(
            row.getLong(0),
            row.getLong(1),
            row.getString(2),
            if (row.getString(3).isNullOrEmpty()) JsonObject() else JsonObject(row.getString(3)),
            row.getLong(4),
            NotificationStatus.valueOf(row.getString(5)),
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}