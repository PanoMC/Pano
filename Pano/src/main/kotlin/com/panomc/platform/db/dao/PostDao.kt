package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.SqlClient

abstract class PostDao : Dao<Post>(Post::class.java) {
    abstract suspend fun removePostCategoriesByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Post?

    abstract suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): Post?

    abstract suspend fun moveTrashById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun moveDraftById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun publishById(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun insert(
        post: Post,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun update(
        userId: Long,
        post: Post,
        sqlClient: SqlClient
    )

    abstract suspend fun updatePostUrlByUrl(
        url: String,
        newUrl: String,
        sqlClient: SqlClient
    )

    abstract suspend fun count(sqlClient: SqlClient): Long

    abstract suspend fun countByCategory(
        id: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByPageType(
        postStatus: PostStatus,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByPageTypeAndSearch(
        postStatus: PostStatus,
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun delete(id: Long, sqlClient: SqlClient)

    abstract suspend fun getByCategory(
        id: Long,
        sqlClient: SqlClient
    ): List<Post>

    /** Posts of one status, newest first: [limit] rows after skipping [offset]. */
    abstract suspend fun getListByPageType(
        postStatus: PostStatus,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Post>

    abstract suspend fun getListByPageTypeAndSearch(
        postStatus: PostStatus,
        search: String,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Post>

    abstract suspend fun getListByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Long,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Post>

    abstract suspend fun countOfPublished(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countOfPublishedByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    ): Long

    /** Published posts, newest first: [limit] rows after skipping [offset]. */
    abstract suspend fun getPublishedList(
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Post>

    /** Published posts of one category, newest first: [limit] rows after skipping [offset]. */
    abstract suspend fun getPublishedListByCategoryId(
        categoryId: Long,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Post>

    /** `url` and `date` of every published post, newest first (the sitemap rows). */
    abstract suspend fun getPublishedUrlsAndDates(
        sqlClient: SqlClient
    ): List<Pair<String, Long>>

    abstract suspend fun getListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Post>

    abstract suspend fun increaseViewByOne(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun increaseViewByOne(
        url: String,
        sqlClient: SqlClient
    )

    abstract suspend fun isPreviousPostExistsByDate(
        date: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isNextPostExistsByDate(
        date: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getPreviousPostByDate(
        date: Long,
        sqlClient: SqlClient
    ): Post?

    abstract suspend fun getNextPostByDate(
        date: Long,
        sqlClient: SqlClient
    ): Post?

    abstract suspend fun updateUserIdByUserId(
        userId: Long,
        newUserId: Long,
        sqlClient: SqlClient
    )
}