package com.panomc.platform.route.api.panel.post.category

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
class PostsByCategoryAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/category/postsByCategory")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val categoryURL = data.getString("url")
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.postCategoryDao.isExistsByURL(categoryURL, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        var category = PostCategory()

        if (categoryURL != "-") {
            category = databaseManager.postCategoryDao.getByURL(categoryURL, sqlConnection) ?: throw Error(
                ErrorCode.UNKNOWN
            )
        }

        val count = databaseManager.postDao.countByCategory(category.id, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val posts = databaseManager.postDao.getListByPageAndCategoryID(category.id, page, sqlConnection)

        if (posts.isEmpty()) {
            return getResults(posts, mapOf(), category, count, totalPage)
        }

        val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

        return getResults(posts, usernameList, category, count, totalPage)
    }

    private fun getResults(
        posts: List<Post>,
        usernameList: Map<Int, String>,
        category: PostCategory,
        count: Int,
        totalPage: Int
    ): Result {
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

        return Successful(
            mutableMapOf<String, Any?>(
                "posts" to postsDataList,
                "postCount" to count,
                "totalPage" to totalPage,
                "category" to category
            )
        )
    }
}