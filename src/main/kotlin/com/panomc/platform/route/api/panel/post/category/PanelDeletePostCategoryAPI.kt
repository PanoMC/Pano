package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelDeletePostCategoryAPI(
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/post/categories/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlConnection = createConnection(context)

        val exists = databaseManager.postCategoryDao.isExistsById(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        databaseManager.postDao.removePostCategoriesByCategoryId(id, sqlConnection)

        databaseManager.postCategoryDao.deleteById(id, sqlConnection)

        return Successful()
    }
}