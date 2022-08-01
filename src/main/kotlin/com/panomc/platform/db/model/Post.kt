package com.panomc.platform.db.model

import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

data class Post(
    val id: Long = -1,
    val title: String,
    val categoryId: Long = -1,
    val writerUserId: Long,
    val text: String,
    val date: Long = System.currentTimeMillis(),
    val moveDate: Long = System.currentTimeMillis(),
    val status: PostStatus = PostStatus.PUBLISHED,
    val image: String,
    val views: Long = 0,
    val url: String
) {
    companion object {
        fun from(row: Row) = Post(
            row.getLong(0),
            row.getString(1),
            row.getLong(2),
            row.getLong(3),
            row.getBuffer(4).toString(),
            row.getLong(5),
            row.getLong(6),
            PostStatus.valueOf(row.getInteger(7))!!,
            row.getBuffer(8).toString(),
            row.getLong(9),
            row.getString(10)
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }
    }
}