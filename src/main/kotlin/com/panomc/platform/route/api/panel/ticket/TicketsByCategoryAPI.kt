package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import kotlin.math.ceil

@Endpoint
class TicketsByCategoryAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/byCategory")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val categoryURL = data.getString("url")
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        var ticketCategory = TicketCategory(-1, "-", "", "-")

        if (categoryURL != "-") {
            val exists = databaseManager.ticketCategoryDao.isExistsByURL(
                categoryURL,
                sqlConnection
            )

            if (!exists) {
                throw Error(ErrorCode.NOT_EXISTS)
            }

            ticketCategory = databaseManager.ticketCategoryDao.getByURL(categoryURL, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)
        }

        val count = databaseManager.ticketDao.countByCategory(ticketCategory.id, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (count == 0) {
            return getResults(listOf(), ticketCategory, mapOf(), 0, totalPage)
        }

        val tickets =
            databaseManager.ticketDao.getAllByPageAndCategoryID(page, ticketCategory.id, sqlConnection)

        if (tickets.isEmpty()) {
            return getResults(listOf(), ticketCategory, mapOf(), 0, totalPage)
        }

        val userIDList = tickets.distinctBy { it.userID }.map { it.userID }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

        return getResults(tickets, ticketCategory, usernameList, count, totalPage)
    }

    private fun getResults(
        tickets: List<Ticket>,
        ticketCategory: TicketCategory,
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

        return Successful(result)
    }
}