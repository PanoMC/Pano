package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.PostStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetPostsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/posts", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                optionalParam(
                    "pageType",
                    arraySchema()
                        .items(enumSchema(*PostStatus.values().map { it.status }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("categoryUrl", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_POSTS, context)

        val parameters = getParameters(context)

        val pageType =
            PostStatus.valueOf(
                status = parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: "published"
            )
                ?: PostStatus.PUBLISHED
        val page = parameters.queryParameter("page")?.long ?: 1L
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string

        var postCategory: PostCategory? = null

        val sqlConnection = createConnection(context)

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

        var totalPage = ceil(count.toDouble() / 10).toLong()

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

        val userIdList = posts.distinctBy { it.writerUserId }.map { it.writerUserId }.filter { it != -1L }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlConnection)

        if (postCategory != null) {
            return getResults(postCategory, posts, usernameList, mapOf(), count, totalPage)
        }

        val categoryIdList = posts.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, posts, usernameList, mapOf(), count, totalPage)
        }

        val categories = databaseManager.postCategoryDao.getByIdList(categoryIdList, sqlConnection)

        return getResults(null, posts, usernameList, categories, count, totalPage)
    }

    private fun getResults(
        postCategory: PostCategory?,
        posts: List<Post>,
        usernameList: Map<Long, String>,
        categories: Map<Long, PostCategory>,
        count: Long,
        totalPage: Long
    ): Result {
        val postsDataList = mutableListOf<Map<String, Any?>>()

        posts.forEach { post ->
            postsDataList.add(
                mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    "category" to
                            (postCategory
                                ?: if (post.categoryId == -1L)
                                    mapOf("id" to -1, "title" to "-", "url" to "-")
                                else
                                    categories.getOrDefault(
                                        post.categoryId,
                                        mapOf("id" to -1, "title" to "-", "url" to "-")
                                    )),
                    "writer" to mapOf(
                        "username" to (usernameList[post.writerUserId] ?: "-")
                    ),
                    "date" to post.date,
                    "views" to post.views,
                    "status" to post.status.value
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