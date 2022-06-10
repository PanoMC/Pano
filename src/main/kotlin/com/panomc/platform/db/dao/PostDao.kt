package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostDao : Dao<Post> {
    suspend fun removePostCategoriesByCategoryId(
        categoryId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Post?

    suspend fun moveTrashById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun moveDraftById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun publishById(
        id: Long,
        userId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun insertAndPublish(
        post: Post,
        sqlConnection: SqlConnection
    ): Long

    suspend fun updateAndPublish(
        userId: Long,
        post: Post,
        sqlConnection: SqlConnection
    )

    suspend fun count(sqlConnection: SqlConnection): Long

    suspend fun countByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun countByPageType(
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): Long

    suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun delete(id: Long, sqlConnection: SqlConnection)

    suspend fun getByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getByPageAndPageType(
        page: Long,
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getByPagePageTypeAndCategoryId(
        page: Long,
        postStatus: PostStatus,
        categoryId: Long,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun countOfPublished(
        sqlConnection: SqlConnection
    ): Long

    suspend fun countOfPublishedByCategoryId(
        categoryId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getPublishedListByPage(
        page: Long,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getPublishedListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun getListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlConnection: SqlConnection
    ): List<Post>

    suspend fun increaseViewByOne(
        id: Long,
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