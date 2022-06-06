package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostDao : Dao<Post> {
    suspend fun removePostCategoriesByCategoryId(
        categoryId: Int,
        sqlConnection: SqlConnection
    )

    suspend fun isExistsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getById(
        id: Int,
        sqlConnection: SqlConnection
    ): Post?

    suspend fun moveTrashById(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun moveDraftById(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun publishById(
        id: Int,
        userId: Int,
        sqlConnection: SqlConnection
    )

    suspend fun insertAndPublish(
        post: Post,
        sqlConnection: SqlConnection
    ): Long

    suspend fun updateAndPublish(
        userId: Int,
        post: Post,
        sqlConnection: SqlConnection
    )

    suspend fun count(sqlConnection: SqlConnection): Int

    suspend fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun countByPageType(
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): Int

    suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun delete(id: Int, sqlConnection: SqlConnection)

    suspend fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getByPageAndPageType(
        page: Int,
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getByPagePageTypeAndCategoryId(
        page: Int,
        postStatus: PostStatus,
        categoryId: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun countOfPublished(
        sqlConnection: SqlConnection
    ): Int

    suspend fun countOfPublishedByCategoryId(
        categoryId: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getPublishedListByPage(
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getPublishedListByPageAndCategoryId(
        categoryId: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getListByPageAndCategoryId(
        categoryId: Int,
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