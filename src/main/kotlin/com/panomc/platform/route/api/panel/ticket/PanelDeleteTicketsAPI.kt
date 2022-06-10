package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.SchemaType
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelDeleteTicketsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.DELETE

    override val routes = arrayListOf("/api/panel/tickets")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("tickets", Schemas.arraySchema().type(SchemaType.NUMBER))
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val selectedTickets = data.getJsonArray("tickets")

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.ticketDao.delete(selectedTickets, sqlConnection)

        databaseManager.ticketMessageDao.deleteByTicketIdList(selectedTickets, sqlConnection)

        return Successful()
    }
}