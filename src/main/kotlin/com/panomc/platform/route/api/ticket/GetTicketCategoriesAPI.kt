package com.panomc.platform.route.api.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class GetTicketCategoriesAPI(
    setupManager: SetupManager,
    authProvider: AuthProvider,
    val databaseManager: DatabaseManager
) : LoggedInApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/ticket/categories")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(optionalParam("page", Schemas.numberSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val page = parameters.queryParameter("page")?.long ?: 0

        val sqlConnection = createConnection(databaseManager, context)

        val categories = databaseManager.ticketCategoryDao.getByPage(page, sqlConnection)

        return Successful(
            mapOf(
                "categories" to categories
            )
        )
    }
}