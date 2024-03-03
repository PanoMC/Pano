package com.panomc.platform.route.api.panel.post


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import kotlin.math.ceil

@Endpoint
class PanelGetPostCategoriesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/post/categories", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(optionalParam("page", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_POSTS, context)

        val parameters = getParameters(context)

        val page = parameters.queryParameter("page")?.long ?: 1L

        val sqlClient = getSqlClient()

        val count = databaseManager.postCategoryDao.getCount(sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val categories = databaseManager.postCategoryDao.getCategories(page, sqlClient)

        val categoryDataList = mutableListOf<Map<String, Any?>>()

        if (categories.isEmpty()) {
            return getResult(categoryDataList, count, totalPage)
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

        return getResult(categoryDataList, count, totalPage)
    }

    private fun getResult(
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Long,
        totalPage: Long
    ) = Successful(
        mutableMapOf<String, Any?>(
            "categories" to categoryDataList,
            "categoryCount" to count,
            "totalPage" to totalPage,
            "host" to "http://"
        )
    )
}