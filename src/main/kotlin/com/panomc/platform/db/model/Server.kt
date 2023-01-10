package com.panomc.platform.db.model

import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Server(
    val id: Long = -1,
    val name: String,
    val motd: String,
    val host: String,
    val port: Int,
    val playerCount: Long,
    val maxPlayerCount: Long,
    val type: ServerType,
    val version: String,
    val favicon: String,
    val permissionGranted: Boolean = false,
    val status: ServerStatus,
    val addedTime: Long = System.currentTimeMillis(),
    val acceptedTime: Long = 0,
    val startTime: Long,
    val stopTime: Long = 0
) {
    companion object {
        fun from(row: Row) = Server(
            row.getLong(0),
            row.getString(1),
            row.getString(2),
            row.getString(3),
            row.getInteger(4),
            row.getLong(5),
            row.getLong(6),
            ServerType.valueOf(row.getString(7)),
            row.getString(8),
            row.getString(9),
            row.getInteger(10) == 1,
            ServerStatus.valueOf(row.getInteger(11))!!,
            row.getLong(12),
            row.getLong(13),
            row.getLong(14),
            row.getLong(15)
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}