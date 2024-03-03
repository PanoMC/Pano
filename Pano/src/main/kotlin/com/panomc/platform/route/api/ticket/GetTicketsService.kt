package com.panomc.platform.route.api.ticket


import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.TicketPageType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.sqlclient.SqlClient
import org.springframework.stereotype.Service
import kotlin.math.ceil

@Service
class GetTicketsService(private val databaseManager: DatabaseManager, private val authProvider: AuthProvider) {
    suspend fun handle(context: RoutingContext, sqlClient: SqlClient, parameters: RequestParameters): Result {
        val pageType =
            TicketPageType.valueOf(
                parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: TicketPageType.ALL.name
            )
        val page = parameters.queryParameter("page")?.long ?: 1L
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string

        var ticketCategory: TicketCategory? = null

        if (categoryUrl != null && categoryUrl != "-") {
            val exists = databaseManager.ticketCategoryDao.existsByUrl(
                categoryUrl,
                sqlClient
            )

            if (!exists) {
                throw NotExists()
            }

            ticketCategory = databaseManager.ticketCategoryDao.getByUrl(categoryUrl, sqlClient)!!
        }

        if (categoryUrl != null && categoryUrl == "-") {
            ticketCategory = TicketCategory()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val count = if (ticketCategory != null)
            databaseManager.ticketDao.countByCategoryAndUserId(ticketCategory.id, userId, sqlClient)
        else
            databaseManager.ticketDao.getCountByPageTypeAndUserId(userId, pageType, sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val tickets = if (ticketCategory != null)
            databaseManager.ticketDao.getAllByPageCategoryIdAndUserId(page, ticketCategory.id, userId, sqlClient)
        else
            databaseManager.ticketDao.getAllByPagePageTypeAndUserId(userId, page, pageType, sqlClient)

        if (tickets.isEmpty()) {
            return getResults(ticketCategory, tickets, mapOf(), null, count, totalPage)
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)

        if (ticketCategory != null) {
            return getResults(ticketCategory, tickets, mapOf(), username, count, totalPage)
        }

        val categoryIdList =
            tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, tickets, mapOf(), username, count, totalPage)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlClient)

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
                    "status" to ticket.status
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