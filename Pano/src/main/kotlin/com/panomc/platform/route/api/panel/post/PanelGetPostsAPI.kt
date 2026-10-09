package com.panomc.platform.route.api.panel.post


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.CategoryNotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.PostStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetPostsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(
                optionalParam(
                    "pageType",
                    arraySchema()
                        .items(enumSchema(*PostStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("categoryUrl", stringSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)

        val pageType =
            PostStatus.valueOf(
                parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: PostStatus.PUBLISHED.name
            )
        val page = Paging.request(context)
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string
        val search = parameters.queryParameter("search")?.string

        var postCategory: PostCategory? = null

        val sqlClient = getSqlClient()

        if (categoryUrl != null && categoryUrl != "-") {
            val isPostCategoryExists = databaseManager.postCategoryDao.existsByUrl(categoryUrl, sqlClient)

            if (!isPostCategoryExists) {
                throw CategoryNotExists()
            }

            postCategory = databaseManager.postCategoryDao.getByUrl(categoryUrl, sqlClient)!!
        }

        if (categoryUrl != null && categoryUrl == "-") {
            postCategory = PostCategory()
        }

        val count = if (search != null)
            databaseManager.postDao.countByPageTypeAndSearch(pageType, search, sqlClient)
        else if (postCategory != null)
            databaseManager.postDao.countByPageTypeAndCategoryId(pageType, postCategory.id, sqlClient)
        else
            databaseManager.postDao.countByPageType(pageType, sqlClient)

        Paging.requireInRange(page, count)

        val posts = if (search != null)
            databaseManager.postDao.getListByPageTypeAndSearch(pageType, search, page.limit, page.offset, sqlClient)
        else if (postCategory != null)
            databaseManager.postDao.getListByPageTypeAndCategoryId(pageType, postCategory.id, page.limit, page.offset, sqlClient)
        else
            databaseManager.postDao.getListByPageType(pageType, page.limit, page.offset, sqlClient)

        if (posts.isEmpty()) {
            return getResults(postCategory, posts, mapOf(), mapOf(), count, page)
        }

        val userIdList = posts.distinctBy { it.writerUserId }.map { it.writerUserId }.filter { it != -1L }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

        if (postCategory != null) {
            return getResults(postCategory, posts, usernameList, mapOf(), count, page)
        }

        val categoryIdList = posts.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, posts, usernameList, mapOf(), count, page)
        }

        val categories = databaseManager.postCategoryDao.getByIdList(categoryIdList, sqlClient)

        return getResults(null, posts, usernameList, categories, count, page)
    }

    private fun getResults(
        postCategory: PostCategory?,
        posts: List<Post>,
        usernameList: Map<Long, String>,
        categories: Map<Long, PostCategory>,
        count: Long,
        page: PageRequest
    ): Result = Successful(payload(postCategory, posts, usernameList, categories, count, page))

    companion object {
        /** The whole response body: `{ items, page }` plus the `category` when the list is filtered. */
        fun payload(
            postCategory: PostCategory?,
            posts: List<Post>,
            usernameList: Map<Long, String>,
            categories: Map<Long, PostCategory>,
            count: Long,
            page: PageRequest
        ): Map<String, Any?> {
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
                        "thumbnailUrl" to post.thumbnailUrl,
                        "views" to post.views,
                        "status" to post.status
                    )
                )
            }

            return Paging.response(
                postsDataList,
                count,
                page,
                if (postCategory != null) mapOf("category" to postCategory) else mapOf()
            )
        }
    }
}