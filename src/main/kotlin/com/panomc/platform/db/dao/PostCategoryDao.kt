package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostCategory
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostCategoryDao : Dao<PostCategory> {
    suspend fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun getCount(sqlConnection: SqlConnection): Int

    suspend fun getByIDList(
        IDList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, PostCategory>

    suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<PostCategory>

    suspend fun getCategories(
        page: Int,
        sqlConnection: SqlConnection
    ): List<PostCategory>

    suspend fun isExistsByURL(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByURLNotByID(
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

    suspend fun getByID(
        id: Int,
        sqlConnection: SqlConnection
    ): PostCategory?

    suspend fun getByURL(
        url: String,
        sqlConnection: SqlConnection
    ): PostCategory?
}