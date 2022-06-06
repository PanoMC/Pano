package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostCategory
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostCategoryDao : Dao<PostCategory> {
    suspend fun isExistsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun deleteById(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun getCount(sqlConnection: SqlConnection): Int

    suspend fun getByIdList(
        idList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, PostCategory>

    suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<PostCategory>

    suspend fun getCategories(
        page: Int,
        sqlConnection: SqlConnection
    ): List<PostCategory>

    suspend fun isExistsByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByUrlNotById(
        url: String,
        id: Int,
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
        id: Int,
        sqlConnection: SqlConnection
    ): PostCategory?

    suspend fun getByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): PostCategory?
}