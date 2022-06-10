package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class TicketCategory(
    val id: Long = -1,
    val title: String = "-",
    val description: String = "",
    val url: String = "-"
) {
    companion object {
        fun from(row: Row) =
            TicketCategory(row.getLong(0), row.getString(1), row.getString(2), row.getString(3))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}