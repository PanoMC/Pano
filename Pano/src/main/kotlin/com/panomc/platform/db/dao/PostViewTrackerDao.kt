package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostViewTracker
import io.vertx.sqlclient.SqlClient

abstract class PostViewTrackerDao : Dao<PostViewTracker>(PostViewTracker::class.java) {
    abstract suspend fun tryRecordView(
        postId: Long,
        viewerHash: String,
        viewedAt: Long,
        dedupeWindowMs: Long,
        sqlClient: SqlClient
    ): Boolean
}
