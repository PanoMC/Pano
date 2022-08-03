package com.panomc.platform.db.model

import com.panomc.platform.util.DateUtil
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class WebsiteView(
    val id: Long = -1,
    val times: Long = 1,
    val date: Long = DateUtil.getTodayInMillis(),
    val ipAddress: String
) {
    companion object {
        fun from(row: Row) = WebsiteView(row.getLong(0), row.getLong(1), row.getLong(2), row.getString(3))

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}