package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class PostsPageInitAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/postPage")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val pageType = data.getInteger("pageType")
        val page = data.getInteger("page")

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                pageType,
                page
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        pageType: Int,
        page: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().postDao.countByPageType(
            pageType,
            sqlConnection,
            (this::countByPageTypeHandler)(handler, sqlConnection, pageType, page)
        )
    }

    private fun countByPageTypeHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        pageType: Int,
        page: Int
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.getByPageAndPageType(
            page,
            pageType,
            sqlConnection,
            (this::getByPageAndPageTypeHandler)(count, totalPage, handler, sqlConnection)
        )
    }

    private fun getByPageAndPageTypeHandler(
        count: Int,
        totalPage: Int,
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
    ) = handler@{ posts: List<Post>?, _: AsyncResult<*> ->
        if (posts == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }
            return@handler
        }

        if (posts.isEmpty()) {
            sendResults(posts, mapOf(), mapOf(), count, totalPage, handler, sqlConnection)

            return@handler
        }

        val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

        databaseManager.getDatabase().userDao.getUsernameByListOfID(
            userIDList,
            sqlConnection,
            (this::getUsernameByListOfIDHandler)(posts, count, totalPage, handler, sqlConnection)
        )
    }

    private fun getUsernameByListOfIDHandler(
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
            sendResults(posts, usernameList, mapOf(), count, totalPage, handler, sqlConnection)

            return@handler
        }

        databaseManager.getDatabase().postCategoryDao.getByIDList(
            categoryIDList,
            sqlConnection,
            (this::getByIDListHandler)(posts, count, totalPage, usernameList, handler, sqlConnection)
        )
    }

    private fun getByIDListHandler(
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
            posts, usernameList, categories, count, totalPage, handler, sqlConnection
        )
    }

    private fun sendResults(
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
                                    mapOf("id" to -1, "title" to "-", "url" to "-")
                                else
                                    categories.getOrDefault(
                                        post.categoryId,
                                        mapOf("id" to -1, "title" to "-", "url" to "-")
                                    ),
                        "writer" to mapOf(
                            "username" to usernameList[post.writerUserID]
                        ),
                        "date" to post.date,
                        "views" to post.views,
                        "status" to post.status
                    )
                )
            }

            handler.invoke(
                Successful(
                    mutableMapOf<String, Any?>(
                        "posts" to postsDataList,
                        "postCount" to count,
                        "totalPage" to totalPage
                    )
                )
            )
        }
    }
}