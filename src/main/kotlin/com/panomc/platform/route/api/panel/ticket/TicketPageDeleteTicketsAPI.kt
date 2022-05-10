package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class TicketPageDeleteTicketsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/delete/selectedTickets")

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

        databaseManager.ticketDao.delete(
            selectedTickets,
            sqlConnection,
            (this::deleteHandler)(handler, sqlConnection, selectedTickets)
        )
    }

    private fun deleteHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        selectedTickets: JsonArray
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.ticketMessageDao.deleteByTicketIDList(
            selectedTickets,
            sqlConnection,
            (this::deleteByTicketIDListHandler)(handler, sqlConnection)
        )
    }

    private fun deleteByTicketIDListHandler(
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