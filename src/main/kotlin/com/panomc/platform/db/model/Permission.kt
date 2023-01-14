package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Permission(val id: Long = -1, val name: String, val iconName: String) {
    companion object {
        fun from(row: Row) = Permission(row.getLong(0), row.getString(1), row.getString(2))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}