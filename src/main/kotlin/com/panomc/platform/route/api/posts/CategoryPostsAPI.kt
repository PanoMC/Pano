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
class CategoryPostsAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/posts/categoryPosts")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val categoryUrl = data.getString("url")
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val isPostCategoryExists = databaseManager.postCategoryDao.isExistsByURL(categoryUrl, sqlConnection)

        if (!isPostCategoryExists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val postCategory = databaseManager.postCategoryDao.getByURL(categoryUrl, sqlConnection) ?: throw Error(
            ErrorCode.UNKNOWN
        )

        val count = databaseManager.postDao.countOfPublishedByCategoryID(postCategory.id, sqlConnection)

        var totalPage = kotlin.math.ceil(count.toDouble() / 5).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val posts =
            databaseManager.postDao.getPublishedListByPageAndCategoryID(postCategory.id, page, sqlConnection)

        if (posts.isEmpty()) {
            return getResult(postCategory, posts, mapOf(), mapOf(), count, totalPage)
        }

        val userIDList = posts.distinctBy { it.writerUserID }.map { it.writerUserID }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

        val categoryIDList = posts.filter { it.categoryId != -1 }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIDList.isEmpty()) {
            return getResult(postCategory, posts, usernameList, mapOf(), count, totalPage)
        }

        val categories = databaseManager.postCategoryDao.getByIDList(categoryIDList, sqlConnection)

        return getResult(postCategory, posts, usernameList, categories, count, totalPage)
    }


    private fun getResult(
        category: PostCategory,
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