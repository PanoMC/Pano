package com.panomc.platform.db.model

import com.panomc.platform.util.TicketStatus
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Ticket(
    val id: Long = -1,
    val title: String,
    val categoryId: Long = -1,
    val userId: Long,
    val date: Long = System.currentTimeMillis(),
    val lastUpdate: Long = System.currentTimeMillis(),
    val status: TicketStatus = TicketStatus.WAITING_REPLY
) {
    companion object {
        fun from(row: Row) = Ticket(
            row.getLong(0),
            row.getString(1),
            row.getLong(2),
            row.getLong(3),
            row.getLong(4),
            row.getLong(5),
            TicketStatus.valueOf(row.getInteger(6))!!
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}