package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.SqlClient

interface PostDao : Dao<Post> {
    suspend fun removePostCategoriesByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    )

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Post?

    suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): Post?

    suspend fun moveTrashById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun moveDraftById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun publishById(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun insert(
        post: Post,
        sqlClient: SqlClient
    ): Long

    suspend fun update(
        userId: Long,
        post: Post,
        sqlClient: SqlClient
    )

    suspend fun updatePostUrlByUrl(
        url: String,
        newUrl: String,
        sqlClient: SqlClient
    )

    suspend fun count(sqlClient: SqlClient): Long

    suspend fun countByCategory(
        id: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun countByPageType(
        postStatus: PostStatus,
        sqlClient: SqlClient
    ): Long

    suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun delete(id: Long, sqlClient: SqlClient)

    suspend fun getByCategory(
        id: Long,
        sqlClient: SqlClient
    ): List<Post>

    suspend fun getByPageAndPageType(
        page: Long,
        postStatus: PostStatus,
        sqlClient: SqlClient
    ): List<Post>

    suspend fun getByPagePageTypeAndCategoryId(
        page: Long,
        postStatus: PostStatus,
        categoryId: Long,
        sqlClient: SqlClient
    ): List<Post>

    suspend fun countOfPublished(
        sqlClient: SqlClient
    ): Long

    suspend fun countOfPublishedByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun getPublishedListByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<Post>

    suspend fun getPublishedListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Post>

    suspend fun getListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Post>

    suspend fun increaseViewByOne(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun increaseViewByOne(
        url: String,
        sqlClient: SqlClient
    )

    suspend fun isPreviousPostExistsByDate(
        date: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isNextPostExistsByDate(
        date: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun getPreviousPostByDate(
        date: Long,
        sqlClient: SqlClient
    ): Post?

    suspend fun getNextPostByDate(
        date: Long,
        sqlClient: SqlClient
    ): Post?

    suspend fun updateUserIdByUserId(
        userId: Long,
        newUserId: Long,
        sqlClient: SqlClient
    )
}