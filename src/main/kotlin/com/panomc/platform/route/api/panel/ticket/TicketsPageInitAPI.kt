package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class TicketsPageInitAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/ticketPage")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val pageType = data.getInteger("page_type")
        val page = data.getInteger("page")

        databaseManager.createConnection((this::createConnectionHandler)(handler, pageType, page))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        pageType: Int,
        page: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().ticketDao.getCountByPageType(
            pageType,
            sqlConnection,
            (this::getCountByPageTypeHandler)(handler, sqlConnection, pageType, page)
        )
    }

    private fun getCountByPageTypeHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        pageType: Int,
        page: Int
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
            }

            return@handler
        }

        databaseManager.getDatabase().ticketDao.getAllByPageAndPageType(
            page,
            pageType,
            sqlConnection,
            (this::getAllByPageAndPageTypeHandler)(handler, sqlConnection, count, totalPage)
        )
    }

    private fun getAllByPageAndPageTypeHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        count: Int,
        totalPage: Int
    ) = handler@{ tickets: List<Ticket>?, _: AsyncResult<*> ->
        if (tickets == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val userIDList = tickets.distinctBy { it.userID }.map { it.userID }

        if (tickets.isNotEmpty()) {
            databaseManager.getDatabase().userDao.getUsernameByListOfID(
                userIDList,
                sqlConnection,
                (this::getUsernameByListOfIDHandler)(handler, sqlConnection, tickets, count, totalPage)
            )

            return@handler
        }

        sendResults(handler, sqlConnection, tickets, mapOf(), mapOf(), count, totalPage)
    }

    private fun getUsernameByListOfIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        count: Int,
        totalPage: Int
    ) = handler@{ usernameList: Map<Int, String>?, _: AsyncResult<*> ->
        if (usernameList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val categoryIDList = tickets.filter { it.categoryID != -1 }.distinctBy { it.categoryID }.map { it.categoryID }

        if (categoryIDList.isEmpty()) {
            sendResults(handler, sqlConnection, tickets, mapOf(), usernameList, count, totalPage)

            return@handler
        }

        databaseManager.getDatabase().ticketCategoryDao.getByIDList(
            categoryIDList,
            sqlConnection,
            (this::getByIDListHandler)(handler, sqlConnection, tickets, usernameList, count, totalPage)
        )
    }

    private fun getByIDListHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        usernameList: Map<Int, String>,
        count: Int,
        totalPage: Int
    ) = handler@{ ticketCategoryList: Map<Int, TicketCategory>?, _: AsyncResult<*> ->
        if (ticketCategoryList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        sendResults(handler, sqlConnection, tickets, ticketCategoryList, usernameList, count, totalPage)
    }

    private fun sendResults(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Int, TicketCategory>,
        usernameList: Map<Int, String>,
        count: Int,
        totalPage: Int
    ) {
        databaseManager.closeConnection(sqlConnection) {
            val ticketDataList = mutableListOf<Map<String, Any?>>()

            tickets.forEach { ticket ->
                ticketDataList.add(
                    mapOf(
                        "id" to ticket.id,
                        "title" to ticket.title,
                        "category" to
                                if (ticket.categoryID == -1)
                                    mapOf("id" to -1, "title" to "-")
                                else
                                    ticketCategoryList.getOrDefault(
                                        ticket.categoryID,
                                        mapOf("id" to -1, "title" to "-")
                                    ),
                        "writer" to mapOf(
                            "username" to usernameList[ticket.userID]
                        ),
                        "date" to ticket.date,
                        "last_update" to ticket.lastUpdate,
                        "status" to ticket.status
                    )
                )
            }

            val result = mutableMapOf<String, Any?>(
                "tickets" to ticketDataList,
                "tickets_count" to count,
                "total_page" to totalPage
            )

            handler.invoke(Successful(result))
        }
    }
}