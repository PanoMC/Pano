package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class TicketPageCloseTicketsAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/close/selectedList")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val selectedTickets = data.getJsonArray("tickets")

        if (selectedTickets.isEmpty) {
            handler.invoke(Successful())

            return
        }

        databaseManager.createConnection((this::createConnectionHandler)(handler, selectedTickets))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        selectedTickets: JsonArray
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().ticketDao.closeTickets(
            selectedTickets,
            sqlConnection,
            (this::closeTicketsHandler)(handler, sqlConnection)
        )
    }

    private fun closeTicketsHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }
}