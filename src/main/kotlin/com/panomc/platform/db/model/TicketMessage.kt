package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class TicketMessage(
    val id: Long = -1,
    val userId: Long,
    val ticketId: Long,
    val message: String,
    val date: Long = System.currentTimeMillis(),
    val panel: Int = 0
) {
    companion object {
        fun from(row: Row) = TicketMessage(
            row.getLong(0),
            row.getLong(1),
            row.getLong(2),
            row.getString(3),
            row.getLong(4),
            row.getInteger(5)
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}