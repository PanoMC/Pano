package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class TicketsByCategoryAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/byCategory")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val categoryURL = data.getString("url")
        val page = data.getInteger("page")

        fun getUsernameByListOfIDHandler(
            sqlConnection: SqlConnection,
            count: Int,
            totalPage: Int,
            tickets: List<Ticket>,
            ticketCategory: TicketCategory
        ) = getUsernameByListOfIDHandler@{ usernameList: Map<Int, String>?, _: AsyncResult<*> ->
            if (usernameList == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@getUsernameByListOfIDHandler
            }

            sendResults(handler, sqlConnection, tickets, ticketCategory, usernameList, count, totalPage)
        }

        fun getAllByPageAndCategoryIDHandler(
            sqlConnection: SqlConnection,
            count: Int,
            totalPage: Int,
            ticketCategory: TicketCategory
        ) = getAllByPageAndCategoryIDHandler@{ tickets: List<Ticket>?, _: AsyncResult<*> ->
            if (tickets == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@getAllByPageAndCategoryIDHandler
            }

            if (tickets.isEmpty()) {
                sendResults(handler, sqlConnection, listOf(), ticketCategory, mapOf(), 0, totalPage)

                return@getAllByPageAndCategoryIDHandler
            }

            val userIDList = tickets.distinctBy { it.userID }.map { it.userID }

            databaseManager.getDatabase().userDao.getUsernameByListOfID(
                userIDList,
                sqlConnection,
                getUsernameByListOfIDHandler(sqlConnection, count, totalPage, tickets, ticketCategory)
            )
        }

        fun countByCategoryHandler(sqlConnection: SqlConnection, ticketCategory: TicketCategory) =
            countByCategoryHandler@{ count: Int?, _: AsyncResult<*> ->
                if (count == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN))
                    }

                    return@countByCategoryHandler
                }

                var totalPage = ceil(count.toDouble() / 10).toInt()

                if (totalPage < 1)
                    totalPage = 1

                if (count == 0) {
                    sendResults(handler, sqlConnection, listOf(), ticketCategory, mapOf(), 0, totalPage)

                    return@countByCategoryHandler
                }

                databaseManager.getDatabase().ticketDao.getAllByPageAndCategoryID(
                    page,
                    ticketCategory.id,
                    sqlConnection,
                    getAllByPageAndCategoryIDHandler(sqlConnection, count, totalPage, ticketCategory)
                )
            }

        fun getByURLHandler(sqlConnection: SqlConnection) =
            getByIDHandler@{ ticketCategory: TicketCategory?, _: AsyncResult<*> ->
                if (ticketCategory == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN))
                    }

                    return@getByIDHandler
                }

                databaseManager.getDatabase().ticketDao.countByCategory(
                    ticketCategory.id,
                    sqlConnection,
                    countByCategoryHandler(sqlConnection, ticketCategory)
                )
            }

        fun isExistsByURLHandler(sqlConnection: SqlConnection) =
            isExistsByIDHandler@{ exists: Boolean?, _: AsyncResult<*> ->
                if (exists == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN))
                    }

                    return@isExistsByIDHandler
                }

                if (!exists) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.NOT_EXISTS))
                    }

                    return@isExistsByIDHandler
                }

                databaseManager.getDatabase().ticketCategoryDao.getByURL(
                    categoryURL,
                    sqlConnection,
                    getByURLHandler(sqlConnection)
                )
            }

        val createConnectionHandler =
            createConnectionHandler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
                if (sqlConnection == null) {
                    handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                    return@createConnectionHandler
                }

                if (categoryURL == "-") {
                    val ticketCategory = TicketCategory(-1, "-", "", "-")

                    databaseManager.getDatabase().ticketDao.countByCategory(
                        ticketCategory.id,
                        sqlConnection,
                        countByCategoryHandler(sqlConnection, ticketCategory)
                    )

                    return@createConnectionHandler
                }

                databaseManager.getDatabase().ticketCategoryDao.isExistsByURL(
                    categoryURL,
                    sqlConnection,
                    isExistsByURLHandler(sqlConnection)
                )
            }

        databaseManager.createConnection(createConnectionHandler)
    }

    private fun sendResults(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        ticketCategory: TicketCategory,
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
                        "category" to ticketCategory,
                        "writer" to mapOf(
                            "username" to usernameList[ticket.userID]
                        ),
                        "date" to ticket.date,
                        "lastUpdate" to ticket.lastUpdate,
                        "status" to ticket.status
                    )
                )
            }

            val result = mutableMapOf<String, Any?>(
                "tickets" to ticketDataList,
                "ticketCount" to count,
                "totalPage" to totalPage,
                "category" to ticketCategory
            )

            handler.invoke(Successful(result))
        }
    }
}