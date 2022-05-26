package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import com.panomc.platform.util.TicketPageType
import io.vertx.ext.web.RoutingContext
import kotlin.math.ceil

@Endpoint
class TicketsPageInitAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/ticketPage")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val pageType = TicketPageType.valueOf(type = data.getString("pageType"))!!
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val count = databaseManager.ticketDao.getCountByPageType(pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val tickets = databaseManager.ticketDao.getAllByPageAndPageType(page, pageType, sqlConnection)

        val userIDList = tickets.distinctBy { it.userID }.map { it.userID }

        if (tickets.isEmpty()) {
            return getResults(tickets, mapOf(), mapOf(), count, totalPage)
        }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)


        val categoryIDList = tickets.filter { it.categoryID != -1 }.distinctBy { it.categoryID }.map { it.categoryID }

        if (categoryIDList.isEmpty()) {
            return getResults(tickets, mapOf(), usernameList, count, totalPage)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIDList(categoryIDList, sqlConnection)

        return getResults(tickets, ticketCategoryList, usernameList, count, totalPage)
    }

    private fun getResults(
        tickets: List<Ticket>,
        ticketCategoryList: Map<Int, TicketCategory>,
        usernameList: Map<Int, String>,
        count: Int,
        totalPage: Int
    ): Result {
        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title,
                    "category" to
                            if (ticket.categoryID == -1)
                                mapOf("id" to -1, "title" to "-", "url" to "-")
                            else
                                ticketCategoryList.getOrDefault(
                                    ticket.categoryID,
                                    mapOf("id" to -1, "title" to "-", "url" to "-")
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

        val result = mutableMapOf<String, Any?>(
            "tickets" to ticketDataList,
            "ticketCount" to count,
            "totalPage" to totalPage
        )

        return Successful(result)
    }
}