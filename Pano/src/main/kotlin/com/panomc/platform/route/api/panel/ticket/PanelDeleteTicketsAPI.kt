package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
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
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/tickets", RouteType.DELETE))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                optionalParam("ids", arraySchema().items(intSchema()))
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_TICKETS, context)

        val parameters = getParameters(context)
        val selectedTickets = parameters.queryParameter("ids").jsonArray

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        val sqlClient = getSqlClient()

        databaseManager.ticketDao.delete(selectedTickets, sqlClient)

        databaseManager.ticketMessageDao.deleteByTicketIdList(selectedTickets, sqlClient)

        return Successful()
    }
}