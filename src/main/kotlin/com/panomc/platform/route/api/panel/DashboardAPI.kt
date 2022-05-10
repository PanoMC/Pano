package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class DashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/initPage/dashboard")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        val result = mutableMapOf<String, Any?>(
            "gettingStartedBlocks" to mapOf(
                "welcomeBoard" to false
            ),
            "registeredPlayerCount" to 0,
            "postCount" to 0,
            "ticketCount" to 0,
            "openTicketCount" to 0,
            "tickets" to mutableListOf<Map<String, Any?>>()
        )

        databaseManager.createConnection((this::createConnectionHandler)(handler, userID, result))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        userID: Int,
        result: MutableMap<String, Any?>
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.systemPropertyDao.isUserInstalledSystemByUserID(
            userID,
            sqlConnection,
            (this::isUserInstalledSystemByUserIDHandler)(handler, sqlConnection, result)
        )
    }

    private fun isUserInstalledSystemByUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ isUserInstalled: Boolean?, _: AsyncResult<*> ->
        if (isUserInstalled == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!isUserInstalled) {
            databaseManager.userDao.count(
                sqlConnection,
                (this::userDaoCountHandler)(handler, sqlConnection, result)
            )

            return@handler
        }

        databaseManager.systemPropertyDao.getValue(
            SystemProperty(
                -1,
                "show_getting_started",
                ""
            ),
            sqlConnection,
            (this::getValueHandler)(handler, sqlConnection, result)
        )
    }

    private fun getValueHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ systemProperty: SystemProperty?, _: AsyncResult<*> ->
        if (systemProperty == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        result["gettingStartedBlocks"] = mapOf(
            "welcomeBoard" to systemProperty.value.toBoolean()
        )

        databaseManager.userDao.count(
            sqlConnection,
            (this::userDaoCountHandler)(handler, sqlConnection, result)
        )
    }

    private fun userDaoCountHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ userCount: Int?, _: AsyncResult<*> ->
        if (userCount == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        result["registeredPlayerCount"] = userCount

        databaseManager.postDao.count(
            sqlConnection,
            (this::postDaoCount)(handler, sqlConnection, result)
        )
    }

    private fun postDaoCount(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ postCount: Int?, _: AsyncResult<*> ->
        if (postCount == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        result["postCount"] = postCount

        databaseManager.ticketDao.count(
            sqlConnection,
            (this::ticketDaoCount)(handler, sqlConnection, result)
        )
    }

    private fun ticketDaoCount(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ ticketCount: Int?, _: AsyncResult<*> ->
        if (ticketCount == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        result["ticketCount"] = ticketCount

        if (ticketCount == 0) {
            sendResults(handler, sqlConnection, result)

            return@handler
        }

        databaseManager.ticketDao.countOfOpenTickets(
            sqlConnection,
            (this::countOfOpenTicketsHandler)(
                handler,
                sqlConnection,
                result
            )
        )
    }

    private fun countOfOpenTicketsHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ openTicketCount: Int?, _: AsyncResult<*> ->
        if (openTicketCount == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        result["openTicketCount"] = openTicketCount

        databaseManager.ticketDao.getLast5Tickets(
            sqlConnection,
            (this::getLast5TicketsHandler)(
                handler,
                sqlConnection,
                result
            )
        )
    }

    private fun getLast5TicketsHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) = handler@{ tickets: List<Ticket>?, _: AsyncResult<*> ->
        if (tickets == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val userIDList = tickets.distinctBy { it.userID }.map { it.userID }

        databaseManager.userDao.getUsernameByListOfID(
            userIDList,
            sqlConnection,
            (this::getUsernameByListOfIDHandler)(
                handler,
                sqlConnection,
                tickets,
                result
            )
        )
    }

    private fun getUsernameByListOfIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        result: MutableMap<String, Any?>
    ) = handler@{ usernameList: Map<Int, String>?, _: AsyncResult<*> ->
        if (usernameList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val categoryIDList = tickets.filter { it.categoryID != -1 }.distinctBy { it.categoryID }.map { it.categoryID }

        if (categoryIDList.isNotEmpty()) {
            databaseManager.ticketCategoryDao.getByIDList(
                categoryIDList,
                sqlConnection,
                (this::getByIDListHandler)(
                    handler,
                    sqlConnection,
                    tickets,
                    usernameList,
                    result
                )
            )

            return@handler
        }

        prepareTickets(handler, sqlConnection, result, tickets, mapOf(), usernameList)
    }

    private fun getByIDListHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        usernameList: Map<Int, String>,
        result: MutableMap<String, Any?>
    ) = handler@{ ticketCategoryList: Map<Int, TicketCategory>?, _: AsyncResult<*> ->
        if (ticketCategoryList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        prepareTickets(handler, sqlConnection, result, tickets, ticketCategoryList, usernameList)
    }

    private fun prepareTickets(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Int, TicketCategory>,
        usernameList: Map<Int, String>
    ) {
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
                    "lastUpdate" to ticket.lastUpdate,
                    "status" to ticket.status
                )
            )
        }

        result["tickets"] = ticketDataList

        sendResults(handler, sqlConnection, result)
    }

    private fun sendResults(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>
    ) {
        databaseManager.closeConnection(sqlConnection) {
            handler.invoke(Successful(result))
        }
    }
}