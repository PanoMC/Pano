package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.SqlConnection

interface PostDao : Dao<Post> {
    suspend fun removePostCategoriesByCategoryId(
        categoryId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Post?

    suspend fun getByUrl(
        url: String,
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

    suspend fun insert(
        post: Post,
        sqlConnection: SqlConnection
    ): Long

    suspend fun update(
        userId: Long,
        post: Post,
        sqlConnection: SqlConnection
    )

    suspend fun updatePostUrlByUrl(
        url: String,
        newUrl: String,
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

    suspend fun increaseViewByOne(
        url: String,
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

    suspend fun updateUserIdByUserId(
        userId: Long,
        newUserId: Long,
        sqlConnection: SqlConnection
    )
}