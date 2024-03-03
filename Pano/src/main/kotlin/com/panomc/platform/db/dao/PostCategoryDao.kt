package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostCategory
import io.vertx.sqlclient.SqlClient

abstract class PostCategoryDao : Dao<PostCategory>(PostCategory::class.java) {
    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getCount(sqlClient: SqlClient): Long

    abstract suspend fun getByIdList(
        idList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, PostCategory>

    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<PostCategory>

    abstract suspend fun getCategories(
        page: Long,
        sqlClient: SqlClient
    ): List<PostCategory>

    abstract suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsByUrlNotById(
        url: String,
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun add(
        postCategory: PostCategory,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun update(
        postCategory: PostCategory,
        sqlClient: SqlClient
    )

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): PostCategory?

    abstract suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): PostCategory?
}