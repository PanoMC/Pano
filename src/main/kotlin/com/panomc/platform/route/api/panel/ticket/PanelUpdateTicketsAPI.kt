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
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateTicketsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.PUT

    override val routes = arrayListOf("/api/panel/tickets")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("tickets", arraySchema().items(intSchema()))
                        .optionalProperty("status", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val selectedTickets = data.getJsonArray("tickets")
        val ticketStatus = data.getString("status")

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        if (ticketStatus != null && ticketStatus == "close") {
            val sqlConnection = createConnection(databaseManager, context)

            databaseManager.ticketDao.closeTickets(selectedTickets, sqlConnection)
        }

        return Successful()
    }
}