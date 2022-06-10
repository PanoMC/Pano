package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class PermissionGroupPerms(val id: Long = -1, val permissionId: Long, val permissionGroupId: Long) {
    companion object {
        fun from(row: Row) = PermissionGroupPerms(row.getLong(0), row.getLong(1), row.getLong(2))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}