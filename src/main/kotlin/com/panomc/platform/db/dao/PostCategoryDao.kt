package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PostCategoryDao : Dao<PostCategory> {
    fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getCount(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit)

    fun getByIDList(
        IDList: List<Int>,
        sqlConnection: SqlConnection,
        handler: (categories: Map<Int, PostCategory>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getAll(
        sqlConnection: SqlConnection,
        handler: (categories: List<PostCategory>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getCategories(
        page: Int,
        sqlConnection: SqlConnection,
        handler: (categories: List<PostCategory>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByURL(
        url: String,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByURLNotByID(
        url: String,
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun add(
        postCategory: PostCategory,
        sqlConnection: SqlConnection,
        handler: (id: Long?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun update(
        postCategory: PostCategory,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (category: PostCategory?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByURL(
        url: String,
        sqlConnection: SqlConnection,
        handler: (category: PostCategory?, asyncResult: AsyncResult<*>) -> Unit
    )
}