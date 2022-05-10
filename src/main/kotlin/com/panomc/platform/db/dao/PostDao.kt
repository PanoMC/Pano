package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Post
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostDao : Dao<Post> {
    fun removePostCategoriesByCategoryID(
        categoryID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (post: Post?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun moveTrashByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun moveDraftByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun publishByID(
        id: Int,
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun insertAndPublish(
        post: Post,
        sqlConnection: SqlConnection,
        handler: (postID: Long?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun updateAndPublish(
        userID: Int,
        post: Post,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit)

    fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun delete(id: Int, sqlConnection: SqlConnection, handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit)

    fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun countOfPublished(
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun countOfPublishedByCategoryID(
        categoryID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPublishedListByPage(
        page: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPublishedListByPageAndCategoryID(
        categoryID: Int,
        page: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getListByPageAndCategoryID(
        categoryID: Int,
        page: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun increaseViewByOne(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isPreviousPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isNextPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPreviousPostByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (post: Post?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getNextPostByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (post: Post?, asyncResult: AsyncResult<*>) -> Unit
    )
}