package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.PostStatus
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetPostsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/posts")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .queryParameter(
                optionalParam(
                    "pageType",
                    arraySchema()
                        .items(enumSchema(*PostStatus.values().map { it.type }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("page", intSchema()))
            .queryParameter(optionalParam("categoryUrl", stringSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val pageType =
            PostStatus.valueOf(
                type = parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: "published"
            )
                ?: PostStatus.PUBLISHED
        val page = parameters.queryParameter("page")?.integer ?: 1
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string

        var postCategory: PostCategory? = null

        val sqlConnection = createConnection(databaseManager, context)

        if (categoryUrl != null && categoryUrl != "-") {
            val isPostCategoryExists = databaseManager.postCategoryDao.isExistsByUrl(categoryUrl, sqlConnection)

            if (!isPostCategoryExists) {
                throw Error(ErrorCode.CATEGORY_NOT_EXISTS)
            }

            postCategory =
                databaseManager.postCategoryDao.getByUrl(categoryUrl, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)
        }

        if (categoryUrl != null && categoryUrl == "-") {
            postCategory = PostCategory()
        }

        val count = if (postCategory != null)
            databaseManager.postDao.countByPageTypeAndCategoryId(pageType, postCategory.id, sqlConnection)
        else
            databaseManager.postDao.countByPageType(pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val posts = if (postCategory != null)
            databaseManager.postDao.getByPagePageTypeAndCategoryId(page, pageType, postCategory.id, sqlConnection)
        else
            databaseManager.postDao.getByPageAndPageType(page, pageType, sqlConnection)

        if (posts.isEmpty()) {
            return getResults(postCategory, posts, mapOf(), mapOf(), count, totalPage)
        }

        val userIdList = posts.distinctBy { it.writerUserId }.map { it.writerUserId }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlConnection)

        if (postCategory != null) {
            return getResults(postCategory, posts, usernameList, mapOf(), count, totalPage)
        }

        val categoryIdList = posts.filter { it.categoryId != -1 }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, posts, usernameList, mapOf(), count, totalPage)
        }

        val categories = databaseManager.postCategoryDao.getByIdList(categoryIdList, sqlConnection)

        return getResults(null, posts, usernameList, categories, count, totalPage)
    }

    private fun getResults(
        postCategory: PostCategory?,
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
                            (postCategory
                                ?: if (post.categoryId == -1)
                                    mapOf("id" to -1, "title" to "-", "url" to "-")
                                else
                                    categories.getOrDefault(
                                        post.categoryId,
                                        mapOf("id" to -1, "title" to "-", "url" to "-")
                                    )),
                    "writer" to mapOf(
                        "username" to usernameList[post.writerUserId]
                    ),
                    "date" to post.date,
                    "views" to post.views,
                    "status" to post.status
                )
            )
        }

        val result = mutableMapOf<String, Any?>(
            "posts" to postsDataList,
            "postCount" to count,
            "totalPage" to totalPage
        )

        if (postCategory != null) {
            result["category"] = postCategory
        }

        return Successful(result)
    }
}