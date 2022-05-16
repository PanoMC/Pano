package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostDao : Dao<Post> {
    suspend fun removePostCategoriesByCategoryID(
        categoryID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Post?

    suspend fun moveTrashByID(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun moveDraftByID(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun publishByID(
        id: Int,
        userID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun insertAndPublish(
        post: Post,
        sqlConnection: SqlConnection
    ): Long

    suspend fun updateAndPublish(
        userID: Int,
        post: Post,
        sqlConnection: SqlConnection
    )

    suspend fun count(sqlConnection: SqlConnection): Int

    suspend fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun delete(id: Int, sqlConnection: SqlConnection)

    suspend fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun countOfPublished(
        sqlConnection: SqlConnection
    ): Int

    suspend fun countOfPublishedByCategoryID(
        categoryID: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getPublishedListByPage(
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getPublishedListByPageAndCategoryID(
        categoryID: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getListByPageAndCategoryID(
        categoryID: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun increaseViewByOne(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun isPreviousPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isNextPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getPreviousPostByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Post?

    suspend fun getNextPostByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Post?
}