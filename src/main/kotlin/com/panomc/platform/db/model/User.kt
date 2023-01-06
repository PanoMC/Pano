package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class User(
    val id: Long = -1,
    val username: String,
    val email: String,
    val registeredIp: String,
    val permissionGroupId: Long = -1,
    val registerDate: Long = System.currentTimeMillis(),
    val lastLoginDate: Long = System.currentTimeMillis(),
    val emailVerified: Int = 0,
    val banned: Int = 0,
    val lastActivityTime: Long = System.currentTimeMillis(),
    val lastPanelActivityTime: Long = System.currentTimeMillis(),
) {
    companion object {
        fun from(row: Row) = User(
            row.getLong(0),
            row.getString(1),
            row.getString(2),
            row.getString(3),
            row.getLong(4),
            row.getLong(5),
            row.getLong(6),
            row.getInteger(7),
            row.getInteger(8),
            row.getLong(9),
            row.getLong(10)
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}