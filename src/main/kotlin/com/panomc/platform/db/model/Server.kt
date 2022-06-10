package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Server(
    val id: Long = -1,
    val name: String,
    val playerCount: Long,
    val maxPlayerCount: Long,
    val type: String,
    val version: String,
    val favicon: String,
    val status: String
) {
    companion object {
        fun from(row: Row) = Server(
            row.getLong(0),
            row.getString(1),
            row.getLong(2),
            row.getLong(3),
            row.getString(4),
            row.getString(5),
            row.getString(6),
            row.getString(7)
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}