package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

@Endpoint
class PostCategoryPageInitAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/posts/categoryPage")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val page = data.getInteger("page")

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                page
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        page: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.postCategoryDao.getCount(
            sqlConnection,
            (this::getCountHandler)(handler, sqlConnection, page)
        )
    }

    private fun getCountHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
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

        databaseManager.postCategoryDao.getCategories(
            page,
            sqlConnection,
            (this::getCategoriesHandler)(handler, sqlConnection, count, totalPage)
        )
    }

    private fun getCategoriesHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        count: Int,
        totalPage: Int
    ) = handler@{ categories: List<PostCategory>?, _: AsyncResult<*> ->
        if (categories == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val categoryDataList = mutableListOf<Map<String, Any?>>()

        val handlers: List<(handler: () -> Unit) -> Any> =
            categories.map { category ->
                val localHandler: (handler: () -> Unit) -> Any = { localHandler ->
                    databaseManager.postDao.countByCategory(
                        category.id,
                        sqlConnection,
                        (this::countByCategoryHandler)(handler, sqlConnection, localHandler, category, categoryDataList)
                    )
                }

                localHandler
            }

        var currentIndex = -1

        fun invoke() {
            val localHandler: () -> Unit = {
                if (currentIndex == handlers.lastIndex)
                    returnResult(handler, sqlConnection, categoryDataList, count, totalPage)
                else
                    invoke()
            }

            currentIndex++

            if (currentIndex <= handlers.lastIndex)
                handlers[currentIndex].invoke(localHandler)
        }

        if (categories.isNotEmpty()) {
            invoke()

            return@handler
        }

        returnResult(handler, sqlConnection, categoryDataList, count, totalPage)
    }

    private fun returnResult(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Int,
        totalPage: Int
    ) {
        databaseManager.closeConnection(sqlConnection) {
            val result = mutableMapOf<String, Any?>(
                "categories" to categoryDataList,
                "categoryCount" to count,
                "totalPage" to totalPage,
                "host" to "http://"
            )

            handler.invoke(Successful(result))
        }
    }

    private fun countByCategoryHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        localHandler: () -> Unit,
        category: PostCategory,
        categoryDataList: MutableList<Map<String, Any?>>
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.postDao.getByCategory(
            category.id,
            sqlConnection,
            (this::getByCategoryHandler)(handler, sqlConnection, localHandler, category, categoryDataList, count)
        )
    }

    private fun getByCategoryHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        localHandler: () -> Unit,
        category: PostCategory,
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Int
    ) = handler@{ posts: List<Post>?, _: AsyncResult<*> ->
        if (posts == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val postsDataList = mutableListOf<Map<String, Any?>>()

        posts.forEach { post ->
            postsDataList.add(
                mapOf(
                    "id" to post.id,
                    "title" to post.title
                )
            )
        }

        categoryDataList.add(
            mapOf(
                "id" to category.id,
                "title" to category.title,
                "description" to category.description,
                "url" to category.url,
                "color" to category.color,
                "postCount" to count,
                "posts" to postsDataList
            )
        )

        localHandler.invoke()
    }
}