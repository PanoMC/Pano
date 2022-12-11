package com.panomc.platform.db.model

import com.panomc.platform.util.ServerStatus
import com.panomc.platform.util.ServerType
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Server(
    val id: Long = -1,
    val name: String,
    val playerCount: Long,
    val maxPlayerCount: Long,
    val type: ServerType,
    val version: String,
    val favicon: String,
    val status: ServerStatus
) {
    companion object {
        fun from(row: Row) = Server(
            row.getLong(0),
            row.getString(1),
            row.getLong(2),
            row.getLong(3),
            ServerType.valueOf(row.getString(4)),
            row.getString(5),
            row.getString(6),
            ServerStatus.valueOf(row.getInteger(7))!!
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}