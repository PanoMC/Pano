package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostCategory
import io.vertx.sqlclient.SqlClient

interface PostCategoryDao : Dao<PostCategory> {
    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun getCount(sqlClient: SqlClient): Long

    suspend fun getByIdList(
        idList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, PostCategory>

    suspend fun getAll(
        sqlClient: SqlClient
    ): List<PostCategory>

    suspend fun getCategories(
        page: Long,
        sqlClient: SqlClient
    ): List<PostCategory>

    suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun existsByUrlNotById(
        url: String,
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun add(
        postCategory: PostCategory,
        sqlClient: SqlClient
    ): Long

    suspend fun update(
        postCategory: PostCategory,
        sqlClient: SqlClient
    )

    suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): PostCategory?

    suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): PostCategory?
}