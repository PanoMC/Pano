package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class SystemProperty(val id: Long = -1, val option: String, val value: String = "") {
    companion object {
        fun from(row: Row) = SystemProperty(row.getLong(0), row.getString(1), row.getString(2))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}