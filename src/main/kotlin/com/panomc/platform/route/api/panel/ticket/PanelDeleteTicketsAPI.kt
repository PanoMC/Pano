package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema

@Endpoint
class PanelDeleteTicketsAPI(
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/tickets", RouteType.DELETE))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                optionalParam("ids", arraySchema().items(intSchema()))
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val selectedTickets = parameters.queryParameter("ids").jsonArray

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.ticketDao.delete(selectedTickets, sqlConnection)

        databaseManager.ticketMessageDao.deleteByTicketIdList(selectedTickets, sqlConnection)

        return Successful()
    }
}