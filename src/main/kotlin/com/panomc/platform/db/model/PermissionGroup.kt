package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class PermissionGroup(val id: Long = -1, val name: String) {
    companion object {
        fun from(row: Row) = PermissionGroup(row.getLong(0), row.getString(1))
        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}