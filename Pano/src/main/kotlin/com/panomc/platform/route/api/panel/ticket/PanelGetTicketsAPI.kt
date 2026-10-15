package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.TicketPageType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetTicketsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/tickets", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(
                optionalParam(
                    "pageType", arraySchema().items(enumSchema(*TicketPageType.entries.map { it.name }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("categoryUrl", stringSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)

        val pageType =
            TicketPageType.valueOf(
                parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: TicketPageType.ALL.name
            )
        val page = Paging.request(context)
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string
        val search = parameters.queryParameter("search")?.string

        var ticketCategory: TicketCategory? = null

        val sqlClient = getSqlClient()

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

        val count = if (search != null)
            databaseManager.ticketDao.getCountByPageTypeAndSearch(pageType, search, sqlClient)
        else if (ticketCategory != null)
            databaseManager.ticketDao.countByCategory(ticketCategory.id, sqlClient)
        else
            databaseManager.ticketDao.getCountByPageType(pageType, sqlClient)

        Paging.requireInRange(page, count)

        val tickets = if (search != null)
            databaseManager.ticketDao.getListByPageTypeAndSearch(pageType, search, page.limit, page.offset, sqlClient)
        else if (ticketCategory != null)
            databaseManager.ticketDao.getListByCategoryId(ticketCategory.id, page.limit, page.offset, sqlClient)
        else
            databaseManager.ticketDao.getListByPageType(pageType, page.limit, page.offset, sqlClient)

        if (tickets.isEmpty()) {
            return getResults(ticketCategory, tickets, mapOf(), mapOf(), count, page)
        }

        val userIdList = tickets.distinctBy { it.userId }.map { it.userId }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

        if (ticketCategory != null) {
            return getResults(ticketCategory, tickets, mapOf(), usernameList, count, page)
        }

        val categoryIdList =
            tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, tickets, mapOf(), usernameList, count, page)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlClient)

        return getResults(null, tickets, ticketCategoryList, usernameList, count, page)
    }

    private fun getResults(
        ticketCategory: TicketCategory?,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Long, TicketCategory>,
        usernameList: Map<Long, String>,
        count: Long,
        page: PageRequest
    ): Result = Successful(payload(ticketCategory, tickets, ticketCategoryList, usernameList, count, page))

    companion object {
        /** The whole response body: `{ items, page }` plus the `category` when the list is filtered. */
        fun payload(
            ticketCategory: TicketCategory?,
            tickets: List<Ticket>,
            ticketCategoryList: Map<Long, TicketCategory>,
            usernameList: Map<Long, String>,
            count: Long,
            page: PageRequest
        ): Map<String, Any?> {
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
                            "username" to usernameList[ticket.userId]
                        ),
                        "date" to ticket.date,
                        "lastUpdate" to ticket.lastUpdate,
                        "status" to ticket.status
                    )
                )
            }

            return Paging.response(
                ticketDataList,
                count,
                page,
                if (ticketCategory != null) mapOf("category" to ticketCategory) else mapOf()
            )
        }
    }
}