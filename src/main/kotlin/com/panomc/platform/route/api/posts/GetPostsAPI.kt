package com.panomc.platform.route.api.posts

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class GetPostsAPI(
    private val databaseManager: DatabaseManager,
    private val getPostsService: GetPostsService
) : Api() {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/posts")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .queryParameter(Parameters.optionalParam("page", Schemas.intSchema()))
            .queryParameter(Parameters.optionalParam("categoryUrl", Schemas.stringSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val sqlConnection = createConnection(databaseManager, context)

        return getPostsService.handle(parameters, sqlConnection)
    }
}