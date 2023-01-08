package com.panomc.platform.db.model

import com.panomc.platform.token.TokenType
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Token(
    val id: Long = -1,
    val subject: String,
    val token: String,
    val type: TokenType,
    val expireDate: Long,
    val startDate: Long = System.currentTimeMillis()
) {
    companion object {
        fun from(row: Row) =
            Token(
                row.getLong(0),
                row.getString(1),
                row.getString(2),
                TokenType.valueOf(row.getString(3)),
                row.getLong(4),
                row.getLong(5)
            )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}