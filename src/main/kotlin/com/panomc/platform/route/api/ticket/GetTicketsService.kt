package com.panomc.platform.route.api.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.TicketPageType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.sqlclient.SqlConnection
import org.springframework.stereotype.Service
import kotlin.math.ceil

@Service
class GetTicketsService(private val databaseManager: DatabaseManager, private val authProvider: AuthProvider) {
    suspend fun handle(context: RoutingContext, sqlConnection: SqlConnection, parameters: RequestParameters): Result {
        val pageType =
            TicketPageType.valueOf(type = parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: "all")
                ?: TicketPageType.ALL
        val page = parameters.queryParameter("page")?.integer ?: 1

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val count = databaseManager.ticketDao.getCountByPageTypeAndUserId(userId, pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val tickets = databaseManager.ticketDao.getAllByPagePageTypeAndUserId(userId, page, pageType, sqlConnection)

        if (tickets.isEmpty()) {
            return getResults(tickets, mapOf(), null, count, totalPage)
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)

        val categoryIdList =
            tickets.filter { it.categoryId != -1 }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(tickets, mapOf(), username, count, totalPage)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlConnection)

        return getResults(tickets, ticketCategoryList, username, count, totalPage)
    }

    private fun getResults(
        tickets: List<Ticket>,
        ticketCategoryList: Map<Int, TicketCategory>,
        username: String?,
        count: Int,
        totalPage: Int
    ): Result {
        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title,
                    "category" to ticketCategoryList.getOrDefault(
                        ticket.categoryId,
                        mapOf("id" to -1, "title" to "-", "url" to "-")
                    ),
                    "writer" to mapOf(
                        "username" to username
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