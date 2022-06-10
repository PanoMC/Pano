package com.panomc.platform.db.model

import com.panomc.platform.util.NotificationStatus
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class PanelNotification(
    val id: Long = -1,
    val userId: Long,
    val typeId: String,
    val date: Long = System.currentTimeMillis(),
    val status: NotificationStatus = NotificationStatus.NOT_READ
) {
    companion object {
        fun from(row: Row) = PanelNotification(
            row.getLong(0),
            row.getLong(1),
            row.getString(2),
            row.getLong(3),
            NotificationStatus.valueOf(row.getString(4))
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}