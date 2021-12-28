package com.panomc.platform.route.api.posts

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import util.StringUtil
import java.lang.Math.ceil

class CategoryPostsAPI : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/posts/categoryPosts")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val categoryUrl = data.getString("url")
        val page = data.getInteger("page")

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                categoryUrl,
                page
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        categoryUrl: String,
        page: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().postCategoryDao.isExistsByURL(
            categoryUrl,
            sqlConnection,
            (this::isExistsByURLHandler)(handler, sqlConnection, categoryUrl, page)
        )
    }

    private fun isExistsByURLHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        categoryUrl: String,
        page: Int
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.NOT_EXISTS))
            }

            return@handler
        }

        databaseManager.getDatabase().postCategoryDao.getByURL(
            categoryUrl,
            sqlConnection,
            (this::getByURLHandler)(handler, sqlConnection, page)
        )
    }

    private fun getByURLHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        page: Int
    ) = handler@{ category: PostCategory?, _: AsyncResult<*> ->
        if (category == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.countOfPublishedByCategoryID(
            category.id,
            sqlConnection,
            (this::countOfPublishedByCategoryID)(handler, sqlConnection, page, category)
        )
    }

    private fun countOfPublishedByCategoryID(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        page: Int,
        category: PostCategory
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        var totalPage = ceil(count.toDouble() / 5).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.getPublishedListByPageAndCategoryID(
            category.id,
            page,
            sqlConnection,
            (this::getPublishedListByPageHandler)(handler, sqlConnection, count, totalPage, category)
        )
    }

    private fun getPublishedListByPageHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        count: Int,
        totalPage: Int,
        category: PostCategory
    ) = handler@{ posts: List<Post>?, _: AsyncResult<*> ->
        if (posts == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }
            return@handler
        }

        if (posts.isEmpty()) {
            sendResults(category, posts, mapOf(), mapOf(), count, totalPage, handler, sqlConnection)

            return@handler
        }

        val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

        databaseManager.getDatabase().userDao.getUsernameByListOfID(
            userIDList,
            sqlConnection,
            (this::getUsernameByListOfIDHandler)(category, posts, count, totalPage, handler, sqlConnection)
        )
    }

    private fun getUsernameByListOfIDHandler(
        category: PostCategory,
        posts: List<Post>,
        count: Int,
        totalPage: Int,
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
    ) = handler@{ usernameList: Map<Int, String>?, _: AsyncResult<*> ->
        if (usernameList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val categoryIDList = posts.filter { it.categoryId != -1 }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIDList.isEmpty()) {
            sendResults(category, posts, usernameList, mapOf(), count, totalPage, handler, sqlConnection)

            return@handler
        }

        databaseManager.getDatabase().postCategoryDao.getByIDList(
            categoryIDList,
            sqlConnection,
            (this::getByIDListHandler)(category, posts, count, totalPage, usernameList, handler, sqlConnection)
        )
    }

    private fun getByIDListHandler(
        category: PostCategory,
        posts: List<Post>,
        count: Int,
        totalPage: Int,
        usernameList: Map<Int, String>,
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ categories: Map<Int, PostCategory>?, _: AsyncResult<*> ->
        if (categories == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        sendResults(
            category, posts, usernameList, categories, count, totalPage, handler, sqlConnection
        )
    }

    private fun sendResults(
        category: PostCategory,
        posts: List<Post>,
        usernameList: Map<Int, String>,
        categories: Map<Int, PostCategory>,
        count: Int,
        totalPage: Int,
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) {
        databaseManager.closeConnection(sqlConnection) {
            val postsDataList = mutableListOf<Map<String, Any?>>()

            posts.forEach { post ->
                postsDataList.add(
                    mapOf(
                        "id" to post.id,
                        "title" to post.title,
                        "category" to
                                if (post.categoryId == -1)
                                    mapOf("id" to -1, "title" to "-")
                                else
                                    categories.getOrDefault(
                                        post.categoryId,
                                        mapOf("id" to -1, "title" to "-")
                                    ),
                        "text" to StringUtil.truncateHTML(post.text, 500, "&hellip;"),
                        "writer" to mapOf(
                            "username" to usernameList[post.writerUserID]
                        ),
                        "date" to post.date,
                        "image" to post.image,
                        "views" to post.views,
                    )
                )
            }

            handler.invoke(
                Successful(
                    mutableMapOf<String, Any?>(
                        "posts" to postsDataList,
                        "posts_count" to count,
                        "total_page" to totalPage,
                        "category" to category
                    )
                )
            )
        }
    }
}