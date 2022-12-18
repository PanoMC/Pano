package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class PanelConfig(
    val id: Long = -1,
    val userId: Long,
    val option: String,
    val value: String
) {
    companion object {
        fun from(row: Row) = PanelConfig(
            row.getLong(0),
            row.getLong(1),
            row.getString(2),
            row.getString(3),
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}