package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostCategory
import io.vertx.sqlclient.SqlConnection

interface PostCategoryDao : Dao<PostCategory> {
    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun getCount(sqlConnection: SqlConnection): Long

    suspend fun getByIdList(
        idList: List<Long>,
        sqlConnection: SqlConnection
    ): Map<Long, PostCategory>

    suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<PostCategory>

    suspend fun getCategories(
        page: Long,
        sqlConnection: SqlConnection
    ): List<PostCategory>

    suspend fun isExistsByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByUrlNotById(
        url: String,
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun add(
        postCategory: PostCategory,
        sqlConnection: SqlConnection
    ): Long

    suspend fun update(
        postCategory: PostCategory,
        sqlConnection: SqlConnection
    )

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): PostCategory?

    suspend fun getByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): PostCategory?
}