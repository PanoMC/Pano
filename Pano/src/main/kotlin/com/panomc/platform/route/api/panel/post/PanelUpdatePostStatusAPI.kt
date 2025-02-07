package com.panomc.platform.route.api.panel.post


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.auth.panel.log.UpdatedPostStatusLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.PostStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdatePostStatusAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/posts/:id/status", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("to", enumSchema(*PostStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_POSTS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val moveTo = PostStatus.valueOf(data.getString("to"))

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val post = databaseManager.postDao.getById(id, sqlClient) ?: throw NotExists()

        if (moveTo == PostStatus.TRASH) {
            databaseManager.postDao.moveTrashById(id, sqlClient)
        }

        if (moveTo == PostStatus.DRAFT) {
            databaseManager.postDao.moveDraftById(id, sqlClient)
        }

        if (moveTo == PostStatus.PUBLISHED) {
            databaseManager.postDao.publishById(id, userId, sqlClient)
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedPostStatusLog(userId, username, post.title, moveTo.name),
            sqlClient
        )

        return Successful()
    }
}