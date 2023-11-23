package com.panomc.platform.route.api.panel.post


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelGetPostAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/posts/:id", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_POSTS, context)

        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val exists = databaseManager.postDao.existsById(id, sqlClient)

        if (!exists) {
            throw PostNotFound()
        }

        val post = databaseManager.postDao.getById(id, sqlClient)!!

        return Successful(
            mapOf(
                "post" to mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    "category" to post.categoryId,
                    "writerUserId" to post.writerUserId,
                    "text" to post.text,
                    "date" to post.date,
                    "status" to post.status,
                    "thumbnailUrl" to post.thumbnailUrl,
                    "views" to post.views
                )
            )
        )
    }
}