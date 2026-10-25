package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostAutosave
import io.vertx.sqlclient.SqlClient

abstract class PostAutosaveDao : Dao<PostAutosave>(PostAutosave::class.java) {
    abstract suspend fun getByPostId(postId: Long, sqlClient: SqlClient): PostAutosave?

    /** The autosaves of [postIds] by post id, without their text (what a list shows). */
    abstract suspend fun getSummariesByPostIds(postIds: List<Long>, sqlClient: SqlClient): Map<Long, PostAutosave>

    /** Inserts or replaces the row of the same post. */
    abstract suspend fun upsert(autosave: PostAutosave, sqlClient: SqlClient)

    abstract suspend fun deleteByPostId(postId: Long, sqlClient: SqlClient)
}
