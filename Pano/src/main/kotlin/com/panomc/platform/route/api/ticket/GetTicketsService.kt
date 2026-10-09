package com.panomc.platform.route.api.ticket


import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.TicketPageType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.sqlclient.SqlClient
import org.springframework.stereotype.Service

@Service
class GetTicketsService(private val databaseManager: DatabaseManager, private val authProvider: AuthProvider) {
    suspend fun handle(
        context: RoutingContext,
        sqlClient: SqlClient,
        parameters: RequestParameters,
        page: PageRequest
    ): Result {
        val pageType =
            TicketPageType.valueOf(
                parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: TicketPageType.ALL.name
            )
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

        Paging.requireInRange(page, count)

        val tickets = if (ticketCategory != null)
            databaseManager.ticketDao.getAllByCategoryIdAndUserId(
                ticketCategory.id,
                userId,
                page.limit,
                page.offset,
                sqlClient
            )
        else
            databaseManager.ticketDao.getAllByUserIdAndPageType(userId, pageType, page.limit, page.offset, sqlClient)

        if (tickets.isEmpty()) {
            return Successful(payload(ticketCategory, tickets, mapOf(), null, count, page))
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)

        if (ticketCategory != null) {
            return Successful(payload(ticketCategory, tickets, mapOf(), username, count, page))
        }

        val categoryIdList =
            tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return Successful(payload(null, tickets, mapOf(), username, count, page))
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlClient)

        return Successful(payload(null, tickets, ticketCategoryList, username, count, page))
    }

    companion object {
        /** Tickets per page when the client sends no `pageSize` (as before the page shape). */
        const val DEFAULT_PAGE_SIZE = 10

        /** The whole response body: `{ items, page }` plus the `category` when the list is filtered. */
        fun payload(
            ticketCategory: TicketCategory?,
            tickets: List<Ticket>,
            ticketCategoryList: Map<Long, TicketCategory>,
            username: String?,
            count: Long,
            page: PageRequest
        ): Map<String, Any?> {
            val items = tickets.map { ticket ->
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
            }

            return Paging.response(
                items,
                count,
                page,
                if (ticketCategory != null) mapOf("category" to ticketCategory) else mapOf()
            )
        }
    }
}
