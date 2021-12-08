package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class PostsByCategoryAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/category/postsByCategory")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val categoryURL = data.getString("url")
        val page = data.getInteger("page")

        fun getUsernameByListOfIDHandler(
            sqlConnection: SqlConnection,
            category: PostCategory,
            count: Int,
            totalPage: Int,
            posts: List<Post>
        ) = handler@{ usernameList: Map<Int, String>?, _: AsyncResult<*> ->
            if (usernameList == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN_ERROR_221))
                }

                return@handler
            }

            sendResults(
                posts, usernameList, category, count, totalPage, handler, sqlConnection
            )
        }

        fun getListByPageAndCategoryIDHandler(
            sqlConnection: SqlConnection,
            category: PostCategory,
            count: Int,
            totalPage: Int
        ) = getListByPageAndCategoryIDHandler@{ posts: List<Post>?, _: AsyncResult<*> ->
            if (posts == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN_ERROR_82))
                }
                return@getListByPageAndCategoryIDHandler
            }

            if (posts.isEmpty()) {
                sendResults(posts, mapOf(), category, count, totalPage, handler, sqlConnection)

                return@getListByPageAndCategoryIDHandler
            }

            val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

            databaseManager.getDatabase().userDao.getUsernameByListOfID(
                userIDList,
                sqlConnection,
                getUsernameByListOfIDHandler(sqlConnection, category, count, totalPage, posts)
            )
        }

        fun countByCategoryHandler(sqlConnection: SqlConnection, category: PostCategory) =
            countByCategoryHandler@{ count: Int?, _: AsyncResult<*> ->
                if (count == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN_ERROR_233))
                    }

                    return@countByCategoryHandler
                }

                var totalPage = ceil(count.toDouble() / 10).toInt()

                if (totalPage < 1)
                    totalPage = 1

                if (page > totalPage || page < 1) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
                    }

                    return@countByCategoryHandler
                }

                databaseManager.getDatabase().postDao.getListByPageAndCategoryID(
                    category.id,
                    page,
                    sqlConnection,
                    getListByPageAndCategoryIDHandler(sqlConnection, category, count, totalPage)
                )
            }

        fun getByURLHandler(sqlConnection: SqlConnection) =
            getByURLHandler@{ category: PostCategory?, _: AsyncResult<*> ->
                if (category == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN_ERROR_232))
                    }

                    return@getByURLHandler
                }

                databaseManager.getDatabase().postDao.countByCategory(
                    category.id,
                    sqlConnection,
                    countByCategoryHandler(sqlConnection, category)
                )
            }

        fun isExistsByURLHandler(sqlConnection: SqlConnection) =
            isExistsByURLHandler@{ exists: Boolean?, _: AsyncResult<*> ->
                if (exists == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN_ERROR_231))
                    }

                    return@isExistsByURLHandler
                }

                if (!exists) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.NOT_EXISTS))
                    }

                    return@isExistsByURLHandler
                }

                databaseManager.getDatabase().postCategoryDao.getByURL(
                    categoryURL,
                    sqlConnection,
                    getByURLHandler(sqlConnection)
                )
            }

        val createConnectionHandler =
            createConnectionHandler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
                if (sqlConnection == null) {
                    handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                    return@createConnectionHandler
                }

                if (categoryURL == "-") {
                    val nullCategory = PostCategory()

                    databaseManager.getDatabase().postDao.countByCategory(
                        nullCategory.id,
                        sqlConnection,
                        countByCategoryHandler(sqlConnection, nullCategory)
                    )

                    return@createConnectionHandler
                }

                databaseManager.getDatabase().postCategoryDao.isExistsByURL(
                    categoryURL,
                    sqlConnection,
                    isExistsByURLHandler(sqlConnection)
                )
            }

        databaseManager.createConnection(createConnectionHandler)
    }

    private fun sendResults(
        posts: List<Post>,
        usernameList: Map<Int, String>,
        category: PostCategory,
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
                        "category" to category,
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
                        "posts_count" to count,
                        "total_page" to totalPage,
                        "category" to category
                    )
                )
            )
        }
    }
}