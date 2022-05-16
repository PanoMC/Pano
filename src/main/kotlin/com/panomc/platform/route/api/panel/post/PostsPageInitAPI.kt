package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import kotlin.math.ceil

@Endpoint
class PostsPageInitAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/postPage")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val pageType = data.getInteger("pageType")
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val count = databaseManager.postDao.countByPageType(pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val posts = databaseManager.postDao.getByPageAndPageType(page, pageType, sqlConnection)

        if (posts.isEmpty()) {
            return getResults(posts, mapOf(), mapOf(), count, totalPage)
        }

        val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

        val categoryIDList = posts.filter { it.categoryId != -1 }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIDList.isEmpty()) {
            return getResults(posts, usernameList, mapOf(), count, totalPage)
        }

        val categories = databaseManager.postCategoryDao.getByIDList(categoryIDList, sqlConnection)

        return getResults(posts, usernameList, categories, count, totalPage)
    }

    private fun getResults(
        posts: List<Post>,
        usernameList: Map<Int, String>,
        categories: Map<Int, PostCategory>,
        count: Int,
        totalPage: Int
    ): Result {
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

        return Successful(
            mutableMapOf<String, Any?>(
                "posts" to postsDataList,
                "postCount" to count,
                "totalPage" to totalPage
            )
        )
    }
}