package com.panomc.platform.route.api.posts

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import util.StringUtil

@Endpoint
class PostsAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/posts")

    private val prepareResult: (
        List<Post>,
        Map<Int, String>,
        Map<Int, PostCategory>,
        Int,
        Int
    ) -> Successful = { posts, usernameList, categories, count, totalPage ->
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

        Successful(
            mutableMapOf<String, Any?>(
                "posts" to postsDataList,
                "postCount" to count,
                "totalPage" to totalPage
            )
        )
    }

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val count = databaseManager.postDao.countOfPublished(sqlConnection)

        var totalPage = kotlin.math.ceil(count.toDouble() / 5).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val posts = databaseManager.postDao.getPublishedListByPage(page, sqlConnection)

        if (posts.isEmpty()) {
            return prepareResult(posts, mapOf(), mapOf(), count, totalPage)
        }

        val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

        val categoryIDList =
            posts.filter { it.categoryId != -1 }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIDList.isEmpty()) {
            return prepareResult(posts, usernameList, mapOf(), count, totalPage)
        }

        val categories = databaseManager.postCategoryDao.getByIDList(
            categoryIDList,
            sqlConnection
        )

        return prepareResult(posts, usernameList, categories, count, totalPage)
    }
}