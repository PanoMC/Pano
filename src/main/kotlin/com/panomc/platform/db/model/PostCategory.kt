package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class PostCategory(
    val id: Long = -1,
    val title: String = "-",
    val description: String = "",
    val url: String = "-",
    val color: String = ""
) {
    companion object {
        fun from(row: Row) =
            PostCategory(row.getLong(0), row.getString(1), row.getString(2), row.getString(3), row.getString(4))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}