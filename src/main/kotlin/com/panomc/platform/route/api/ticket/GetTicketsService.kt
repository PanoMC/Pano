package com.panomc.platform.route.api.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.TicketStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.sqlclient.SqlConnection
import org.springframework.stereotype.Service
import kotlin.math.ceil

@Service
class GetTicketsService(private val databaseManager: DatabaseManager, private val authProvider: AuthProvider) {
    suspend fun handle(context: RoutingContext, sqlConnection: SqlConnection, parameters: RequestParameters): Result {
        val pageType =
            TicketStatus.valueOf(status = parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: "all")
                ?: TicketStatus.ALL
        val page = parameters.queryParameter("page")?.long ?: 1L
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string

        var ticketCategory: TicketCategory? = null

        if (categoryUrl != null && categoryUrl != "-") {
            val exists = databaseManager.ticketCategoryDao.isExistsByUrl(
                categoryUrl,
                sqlConnection
            )

            if (!exists) {
                throw Error(ErrorCode.NOT_EXISTS)
            }

            ticketCategory = databaseManager.ticketCategoryDao.getByUrl(categoryUrl, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)
        }

        if (categoryUrl != null && categoryUrl == "-") {
            ticketCategory = TicketCategory()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val count = if (ticketCategory != null)
            databaseManager.ticketDao.countByCategoryAndUserId(ticketCategory.id, userId, sqlConnection)
        else
            databaseManager.ticketDao.getCountByPageTypeAndUserId(userId, pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val tickets = if (ticketCategory != null)
            databaseManager.ticketDao.getAllByPageCategoryIdAndUserId(page, ticketCategory.id, userId, sqlConnection)
        else
            databaseManager.ticketDao.getAllByPagePageTypeAndUserId(userId, page, pageType, sqlConnection)

        if (tickets.isEmpty()) {
            return getResults(ticketCategory, tickets, mapOf(), null, count, totalPage)
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)

        if (ticketCategory != null) {
            return getResults(ticketCategory, tickets, mapOf(), username, count, totalPage)
        }

        val categoryIdList =
            tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, tickets, mapOf(), username, count, totalPage)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlConnection)

        return getResults(null, tickets, ticketCategoryList, username, count, totalPage)
    }

    private fun getResults(
        ticketCategory: TicketCategory?,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Long, TicketCategory>,
        username: String?,
        count: Long,
        totalPage: Long
    ): Result {
        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title,
                    "category" to (ticketCategory ?: if (ticket.categoryId == -1L)
                        mapOf("id" to -1, "title" to "-", "url" to "-")
                    else
                        ticketCategoryList.getOrDefault(
                            ticket.categoryId,
                            mapOf("id" to -1, "title" to "-", "url" to "-")
                        )),
                    "writer" to mapOf(
                        "username" to username
                    ),
                    "date" to ticket.date,
                    "lastUpdate" to ticket.lastUpdate,
                    "status" to ticket.status.value
                )
            )
        }

        val result = mutableMapOf<String, Any?>(
            "tickets" to ticketDataList,
            "ticketCount" to count,
            "totalPage" to totalPage
        )

        if (ticketCategory != null) {
            result["category"] = ticketCategory
        }

        return Successful(result)
    }
}