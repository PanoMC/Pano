package com.panomc.platform.db.model

import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class PanelConfig(val id: Long = -1) {
    companion object {
        fun from(row: Row) = PanelConfig(row.getLong(0))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}