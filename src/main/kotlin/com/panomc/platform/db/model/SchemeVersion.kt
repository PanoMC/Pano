package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class SchemeVersion(val key: String, val extra: String? = null) {
    companion object {
        fun from(row: Row) = SchemeVersion(row.getString(0), row.getString(1))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}