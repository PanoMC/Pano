package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.DeletedPostCategoryLog
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelDeletePostCategoryAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/post/categories/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val postCategory = databaseManager.postCategoryDao.getById(id, sqlClient) ?: throw NotExists()

        databaseManager.postDao.removePostCategoriesByCategoryId(id, sqlClient)

        databaseManager.postCategoryDao.deleteById(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(DeletedPostCategoryLog(userId, username, postCategory.title), sqlClient)

        return Successful()
    }
}