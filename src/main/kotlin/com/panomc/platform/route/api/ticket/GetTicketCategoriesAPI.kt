package com.panomc.platform.route.api.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class GetTicketCategoriesAPI(
    val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val paths = listOf(Path("/api/ticket/categories", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(optionalParam("page", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val page = parameters.queryParameter("page")?.long ?: 0

        val sqlConnection = createConnection(context)

        val categories = databaseManager.ticketCategoryDao.getByPage(page, sqlConnection)

        return Successful(
            mapOf(
                "categories" to categories
            )
        )
    }
}