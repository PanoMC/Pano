package com.panomc.platform.route.api.panel.post.category


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetPostCategoriesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/post/categories", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("search", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)

        val page = Paging.request(context)
        val search = parameters.queryParameter("search")?.string

        val sqlClient = getSqlClient()

        val count = if (search != null)
            databaseManager.postCategoryDao.countBySearch(search, sqlClient)
        else
            databaseManager.postCategoryDao.getCount(sqlClient)

        Paging.requireInRange(page, count)

        val categories = if (search != null)
            databaseManager.postCategoryDao.getListBySearch(search, page.limit, page.offset, sqlClient)
        else
            databaseManager.postCategoryDao.getList(page.limit, page.offset, sqlClient)

        val categoryDataList = mutableListOf<Map<String, Any?>>()

        if (categories.isEmpty()) {
            return getResult(categoryDataList, count, page)
        }

        val addCategoryToList =
            { category: PostCategory, count: Long, categoryDataList: MutableList<Map<String, Any?>>, posts: List<Post> ->
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
            }

        val getCategoryData: suspend (PostCategory) -> Unit = { category ->
            val count = databaseManager.postDao.countByCategory(category.id, sqlClient)
            val posts = databaseManager.postDao.getByCategory(category.id, sqlClient)

            addCategoryToList(category, count, categoryDataList, posts)
        }

        categories.forEach {
            getCategoryData(it)
        }

        return getResult(categoryDataList, count, page)
    }

    private fun getResult(
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Long,
        page: PageRequest
    ) = Successful(payload(categoryDataList, count, page))

    companion object {
        /** The whole response body: `{ items, page }` plus the legacy `host` key. */
        fun payload(
            categoryDataList: List<Map<String, Any?>>,
            count: Long,
            page: PageRequest
        ): Map<String, Any?> = Paging.response(categoryDataList, count, page, mapOf("host" to "http://"))
    }
}