package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import java.util.*

data class ServerPlayer(
    val id: Long = -1,
    val uuid: UUID,
    val username: String,
    val ping: Long = 0,
    val serverId: Long,
    val loginTime: Long
) {
    companion object {
        fun from(row: Row) = ServerPlayer(
            row.getLong(0),
            UUID.fromString(row.getString(1)),
            row.getString(2),
            row.getLong(3),
            row.getLong(4),
            row.getLong(5),
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}