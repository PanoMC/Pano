package com.panomc.platform.route.api.posts


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class GetPostPreviewAPI(
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/posts/:id/preview", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val isPostExistsById = databaseManager.postDao.existsById(id, sqlClient)

        if (!isPostExistsById) {
            throw PostNotFound()
        }

        val post = databaseManager.postDao.getById(id, sqlClient)!!
        var postCategory: PostCategory? = null

        if (post.categoryId != -1L) {
            postCategory = databaseManager.postCategoryDao.getById(post.categoryId, sqlClient)!!
        }

        val username = if (post.writerUserId == -1L)
            "-"
        else
            databaseManager.userDao.getUsernameFromUserId(post.writerUserId, sqlClient)!!

        return Successful(
            mapOf(
                "id" to post.id,
                "title" to post.title,
                "category" to
                        if (postCategory == null)
                            mapOf("id" to -1, "title" to "-")
                        else
                            mapOf<String, Any?>(
                                "title" to postCategory.title,
                                "url" to postCategory.url
                            ),
                "writer" to mapOf(
                    "username" to username
                ),
                "text" to post.text,
                "date" to post.date,
                "status" to post.status,
                "thumbnailUrl" to post.thumbnailUrl,
                "views" to post.views
            )
        )
    }
}